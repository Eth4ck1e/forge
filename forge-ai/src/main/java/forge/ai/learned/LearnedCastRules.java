package forge.ai.learned;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.Pair;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.AiProps;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostPayment;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/**
 * learned-ai D44: casting-window rule sets on top of the D33 Sequencing planner, as controlled experiments (the same
 * seeds under different rules; a judge compares them). Profile property {@code LEARNED_CAST_RULES} (default "off" = no
 * code path changes play), sim {@code -castrules [seat<N>:]<name>} / {@code -Dlearned.castRules}; only LEARNED_HOOKS
 * seats, real (not simulated) games.
 * <ul>
 * <li>{@code baseline}: the control. Stock decides every play; only {@code cast_util} records are written.</li>
 * <li>{@code hold-instants}: non-removal instants (class "instant") are held to other players' turns: stock may cast
 * one in response to another player's object on the stack, or while this seat is being attacked; otherwise only at
 * the latest window before our turn, the end step of the player whose next turn is ours, where the planner casts
 * them (stock's own approval sets targets; an untargeted card-flow spell is cast even when stock declines).</li>
 * <li>{@code removal-combat}: instant removal only on another player's turn, during combat after attackers are declared
 * (declare-attackers step: any attacker at us; declare-blockers step: unblocked attackers at us), on a creature
 * attacking this seat; sorcery-speed removal only in our main phase, on the most valuable legal permanent of the top
 * threat (stock board score {@link ComputerUtil#evaluateBoardPosition}).</li>
 * <li>{@code removal-precombat}: instant removal only at the beginning of combat on another player's turn, on that
 * player's best creature able to attack us, when that player is predicted to attack us (it attacked us in its last
 * combat; without history, we have the lowest life among its opponents); sorcery removal as in removal-combat.</li>
 * </ul>
 * Stock's candidate list is filtered ({@link #filter}, AiController) so stock never casts a ruled class outside its
 * window; ruled removal is cast only by the planner ({@link #act}), which sets its own single target. Removal the planner
 * cannot target (X costs, divided damage, several or chained targets, TargetingPlayer) stays with stock ("plan":0).
 */
public final class LearnedCastRules {
    public static final String OFF = "off", BASELINE = "baseline", HOLD = "hold-instants",
            RCOMBAT = "removal-combat", RPRE = "removal-precombat";
    public static final String HOOK = "cast_util";
    static final List<String> NAMES = Arrays.asList(OFF, BASELINE, HOLD, RCOMBAT, RPRE);
    /** sim / JVM override of every Learned seat (null = profile) */
    public static volatile String override = System.getProperty("learned.castRules");
    /** per-seat override (seat number as in SIMRESULT, 1-based); wins over {@link #override} */
    private static final Map<Integer, String> SEAT = new ConcurrentHashMap<>();
    /** untargeted card-flow APIs the planner casts at the hold window even when stock's logic declines */
    static final Set<ApiType> FORCE_APIS = new HashSet<>(Arrays.asList(ApiType.Draw, ApiType.Dig, ApiType.Scry,
            ApiType.Surveil, ApiType.Token, ApiType.ChangeZone, ApiType.Mill, ApiType.Explore));

    private LearnedCastRules() {}

    /** "name" (every Learned seat) or "seat<N>:name", several comma separated; "off" clears */
    public static void setOverride(String spec) {
        if (spec == null) { override = null; SEAT.clear(); return; }
        for (String tok : spec.split(",")) {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            int colon = tok.indexOf(':');
            if (tok.startsWith("seat") && colon > 4) SEAT.put(Integer.parseInt(tok.substring(4, colon)), canonical(tok.substring(colon + 1)));
            else override = canonical(tok);
        }
    }

    public static void clearOverrides() { override = null; SEAT.clear(); }

    static String canonical(String s) {
        for (String n : NAMES) if (n.equalsIgnoreCase(s.trim())) return n;
        throw new IllegalArgumentException("unknown cast rule set '" + s + "' (" + String.join("|", NAMES) + ")");
    }

    private static String lenient(String s) {
        if (s == null) return OFF;
        for (String n : NAMES) if (n.equalsIgnoreCase(s.trim())) return n;
        return OFF;
    }

    /** the seat's rule set; OFF for stock profiles, non-AI seats and simulated games */
    public static String rules(AiController aic, Player ai) {
        if (aic == null || !aic.getBoolProperty(AiProps.LEARNED_HOOKS)) return OFF;
        if (ai.getGame().isSimulationCopy()) return OFF;
        String s = SEAT.isEmpty() ? null : SEAT.get(LearnedHooks.seatNumber(ai));
        if (s == null) { String o = override; s = o != null && !o.trim().isEmpty() ? o : aic.getProperty(AiProps.LEARNED_CAST_RULES); }
        return lenient(s);
    }

    public static String rules(Player ai) {
        if (!(ai.getController() instanceof PlayerControllerAi)) return OFF;
        return rules(((PlayerControllerAi) ai.getController()).getAi(), ai);
    }

    // ---- per-game state -------------------------------------------------------------------------------------------
    /** per attacking seat: [turn, bitmask of seats it attacked] from the last combat with attackers seen */
    private static final Map<Integer, int[]> LAST_ATTACK = new HashMap<>();
    /** per seat: planner cast attempts this step ("turn:phase:saId"), so a failed cast is not retried forever */
    private static final Map<Integer, Set<String>> TRIED = new HashMap<>();
    private static final Map<Integer, Intent> INTENT = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static int[] counts = new int[4];   // planner casts, filtered priorities, records written, errors

    static synchronized void resetGame() {
        LAST_ATTACK.clear(); TRIED.clear(); INTENT.clear(); PENDING.clear(); counts = new int[4];
    }

    public static synchronized int[] counts() { return counts.clone(); }

    // ---- classes ----------------------------------------------------------------------------------------------------
    /** removal kind of a spell's root ability, or null: destroy / exile / damage / shrink (-X/-X) / edict */
    static String removalKind(SpellAbility sa) {
        ApiType api = sa.getApi();
        if (api == null || !sa.usesTargeting() || sa.getTargetRestrictions() == null) return null;
        String valid = String.join(",", sa.getTargetRestrictions().getValidTgts());
        boolean perm = valid.contains("Creature") || valid.contains("Permanent") || valid.contains("Planeswalker")
                || valid.contains("Artifact") || valid.contains("Enchantment") || valid.contains("Battle") || valid.startsWith("Card");
        switch (api) {
            case Destroy: return perm ? "destroy" : null;
            case ChangeZone: {
                String o = sa.getParamOrDefault("Origin", ""), d = sa.getParamOrDefault("Destination", "");
                return perm && o.contains("Battlefield") && (d.equals("Exile") || d.equals("Graveyard")) ? "exile" : null;
            }
            case DealDamage: return valid.contains("Creature") || valid.contains("Any") || valid.contains("Planeswalker") || valid.contains("Permanent") ? "damage" : null;
            case Pump: return sa.getParamOrDefault("NumDef", "").trim().startsWith("-") && perm ? "shrink" : null;
            case Sacrifice: return (valid.contains("Player") || valid.contains("Opponent")) && sa.getParamOrDefault("SacValid", "Creature").contains("Creature") ? "edict" : null;
            default: return null;
        }
    }

    /**
     * Spell class: removal-instant / removal-sorcery (see {@link #removalKind}), counter, mana, sweeper, instant
     * (non-removal instant-speed non-permanent spell), flash (permanent with flash), sorcery, permanent; ability / land.
     */
    static String classify(SpellAbility sa, Player ai) {
        if (sa == null) return "none";
        if (sa.isLandAbility()) return "land";
        if (!sa.isSpell()) return "ability";
        ApiType api = sa.getApi();
        if (api == ApiType.Counter) return "counter";
        if (api == ApiType.Mana) return "mana";
        Card h = sa.getHostCard();
        boolean inst = ManaView.instantSpeed(sa, ai);
        if (removalKind(sa) != null) return inst ? "removal-instant" : "removal-sorcery";
        if (api == ApiType.DestroyAll || api == ApiType.DamageAll || api == ApiType.ChangeZoneAll || api == ApiType.SacrificeAll) return "sweeper";
        if (h != null && h.isPermanent()) return inst ? "flash" : "permanent";
        return inst ? "instant" : "sorcery";
    }

    /** removal the planner can aim itself: one target on the root, nothing else to choose */
    static boolean plannable(SpellAbility sa) {
        if (ManaView.manaCost(sa).countX() > 0) return false;
        if (sa.isDividedAsYouChoose() || sa.hasParam("TargetingPlayer")) return false;
        Card h = sa.getHostCard();
        TargetRestrictions tr = sa.getTargetRestrictions();
        if (tr == null || tr.getMinTargets(h, sa) > 1) return false;
        for (SpellAbility s = sa.getSubAbility(); s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && s.getTargetRestrictions() != null && s.getTargetRestrictions().getMinTargets(h, s) > 0) return false;
            if (s.hasParam("TargetingPlayer")) return false;
        }
        return true;
    }

    // ---- window ------------------------------------------------------------------------------------------------------
    static final class Window {
        PhaseType step; Player active; boolean myTurn, combat, stackEmpty, stackOpp, latestEnd; int stackN;
        List<Card> atMe = new ArrayList<>(), unblockedAtMe = new ArrayList<>();
    }

    static Window window(Player ai) {
        Game game = ai.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        Window w = new Window();
        w.step = ph.getPhase();
        w.active = ph.getPlayerTurn();
        w.myTurn = w.active == ai;
        w.stackEmpty = game.getStack().isEmpty();
        w.stackN = game.getStack().size();
        if (!w.stackEmpty) {
            SpellAbilityStackInstance top = game.getStack().peek();
            w.stackOpp = top != null && top.getActivatingPlayer() != null && top.getActivatingPlayer() != ai;
        }
        Combat c = game.getCombat();
        w.combat = c != null;
        if (c != null && c.getAttackingPlayer() != ai) {
            for (Card a : c.getAttackers()) {
                if (c.getDefenderPlayerByAttacker(a) != ai) continue;
                w.atMe.add(a);
                if (!c.isBlocked(a)) w.unblockedAtMe.add(a);
            }
        }
        w.latestEnd = !w.myTurn && w.step == PhaseType.END_OF_TURN && ph.getNextTurn() == ai;
        return w;
    }

    /** hold-instants: may a non-removal instant be cast now (by stock or the planner) */
    static boolean holdOpen(Window w) {
        if (w.stackOpp) return true;                 // a response to another player's spell or ability
        if (w.myTurn) return false;
        if (!w.atMe.isEmpty()) return true;          // the game state demands it: we are being attacked
        return w.latestEnd;                          // the latest window before our turn
    }

    /** AiController hook: drop the plays this seat's rule set forbids stock to make now (no-op when off / baseline) */
    public static void filter(AiController aic, Player ai, List<SpellAbility> saList) {
        final String r = rules(aic, ai);
        if (r == OFF || r == BASELINE || saList.isEmpty()) return;
        final Window w = window(ai);
        int before = saList.size();
        saList.removeIf(sa -> {
            try {
                String cls = classify(sa, ai);
                if (r == HOLD) return "instant".equals(cls) && !holdOpen(w);
                if (cls.startsWith("removal-")) return plannable(sa);   // ruled removal is the planner's alone
                return false;
            } catch (RuntimeException e) {
                ManaView.errorNote("castrules filter " + sa, e);
                return false;
            }
        });
        if (saList.size() != before) synchronized (LearnedCastRules.class) { counts[1]++; }
    }

    // ---- threats -----------------------------------------------------------------------------------------------------
    /** living opponents by the stock board score (ComputerUtil.evaluateBoardPosition(ai, opp)), highest first; [player, score] */
    static List<Object[]> threats(Player ai) {
        List<Object[]> out = new ArrayList<>();
        for (Player o : ai.getOpponents()) out.add(new Object[] {o, ComputerUtil.evaluateBoardPosition(ai, o)});
        out.sort((x, y) -> Integer.compare((Integer) y[1], (Integer) x[1]));   // stable: turn order breaks ties
        return out;
    }

    static int value(Card c) {
        if (c.isCreature()) return ComputerUtilCard.evaluateCreature(c);
        if (c.isPlaneswalker()) return 50 + c.getCMC() * 20 + c.getCurrentLoyalty() * 10;
        return 25 + c.getCMC() * 15;
    }

    /** would the removal (one target) take this card off the battlefield */
    static boolean kills(SpellAbility sa, Card c) {
        String k = removalKind(sa);
        if (k == null) return false;
        switch (k) {
            case "destroy": return c.canBeDestroyed();
            case "exile": return true;
            case "damage": {
                int dmg = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("NumDmg", "0"), sa);
                int got = ComputerUtilCombat.predictDamageTo(c, dmg, sa.getHostCard(), false);
                if (c.isCreature()) return got >= c.getLethalDamage();
                if (c.isPlaneswalker()) return got >= c.getCurrentLoyalty();
                return false;
            }
            case "shrink": {
                int def = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("NumDef"), sa);
                return c.isCreature() && -def >= c.getNetToughness();
            }
            case "edict": return true;   // the target is the controller; the controller picks the creature
            default: return false;
        }
    }

    /** the entity to aim at for card {@code c}: the card, or its controller for an edict; null when illegal */
    static GameEntity aim(SpellAbility sa, Card c) {
        GameEntity e = "edict".equals(removalKind(sa)) ? c.getController() : c;
        return sa.canTarget(e) ? e : null;
    }

    // ---- planner -----------------------------------------------------------------------------------------------------
    /** the planner's play: ability, target (null = untargeted / stock's), reason */
    static final class Plan { SpellAbility sa; GameEntity target; String why; boolean stockAim; }

    /** own hand and command-zone spells castable now (canPlay, mana, additional costs); call from a saved state */
    static List<SpellAbility> castable(Player ai) {
        List<SpellAbility> out = new ArrayList<>();
        List<Card> pool = new ArrayList<>(ai.getCardsIn(ZoneType.Hand));
        for (Card c : ai.getCardsIn(ZoneType.Command)) if (c.isCommander()) pool.add(c);
        for (Card c : pool) {
            if (c.isLand()) continue;
            for (SpellAbility sa : c.getSpells()) {
                Player prev = sa.getActivatingPlayer();
                try {
                    sa.setActivatingPlayer(ai);
                    if (sa.canPlay() && ComputerUtilMana.canPayManaCost(sa, ai, 0, false)
                            && CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false)) out.add(sa);
                } catch (RuntimeException e) {
                    ManaView.errorNote("castrules castable " + c.getName(), e);
                } finally {
                    sa.setActivatingPlayer(prev);
                }
            }
        }
        out.sort((x, y) -> Integer.compare(ManaView.manaCost(x).getCMC(), ManaView.manaCost(y).getCMC()));   // cheapest first
        return out;
    }

    private static String tryKey(Player ai, SpellAbility sa) {
        PhaseHandler ph = ai.getGame().getPhaseHandler();
        return ph.getTurn() + ":" + (ph.getPhase() == null ? -1 : ph.getPhase().ordinal()) + ":" + sa.getId();
    }

    private static synchronized boolean tried(Player ai, SpellAbility sa) {
        Set<String> s = TRIED.get(LearnedHooks.seatNumber(ai));
        return s != null && s.contains(tryKey(ai, sa));
    }

    private static synchronized void noteTry(Player ai, SpellAbility sa) {
        Set<String> s = TRIED.computeIfAbsent(LearnedHooks.seatNumber(ai), k -> new HashSet<>());
        if (s.size() > 200) s.clear();
        s.add(tryKey(ai, sa));
    }

    /** the best kill on {@code targets} (value order) by the cheapest ruled removal of {@code cls}; from a saved state */
    static Plan pickRemoval(Player ai, String cls, List<Card> targets, String why) {
        List<Card> order = new ArrayList<>(targets);
        order.sort((x, y) -> Integer.compare(value(y), value(x)));
        List<SpellAbility> spells = new ArrayList<>();
        for (SpellAbility sa : castable(ai)) {
            Player prev = sa.getActivatingPlayer();
            sa.setActivatingPlayer(ai);
            try {
                if (cls.equals(classify(sa, ai)) && plannable(sa) && !tried(ai, sa)) spells.add(sa);
            } finally { sa.setActivatingPlayer(prev); }
        }
        if (spells.isEmpty()) return null;
        for (Card c : order) {
            for (SpellAbility sa : spells) {
                Player prev = sa.getActivatingPlayer();
                sa.setActivatingPlayer(ai);
                try {
                    if (!kills(sa, c)) continue;
                    GameEntity e = aim(sa, c);
                    if (e == null) continue;
                    Plan p = new Plan();
                    p.sa = sa; p.target = e; p.why = why;
                    return p;
                } catch (RuntimeException ex) {
                    ManaView.errorNote("castrules aim " + sa, ex);
                } finally { sa.setActivatingPlayer(prev); }
            }
        }
        return null;
    }

    /** removal-precombat: is the active player predicted to attack us (public history, else lowest life) */
    static synchronized String predictsAttack(Player ai, Player active) {
        int[] last = LAST_ATTACK.get(LearnedHooks.seatNumber(active));
        int me = LearnedHooks.seatNumber(ai);
        if (last != null) return (last[1] & (1 << me)) != 0 ? "history" : null;
        int min = Integer.MAX_VALUE;
        for (Player o : active.getOpponents()) min = Math.min(min, o.getLife());
        return ai.getLife() <= min ? "lowlife" : null;
    }

    /** the planner's play under {@code r} now (from a saved state), or null */
    static Plan plan(Player ai, String r) {
        Window w = window(ai);
        if (w.step == null) return null;
        if (r == HOLD) {
            if (!w.latestEnd || !w.stackEmpty) return null;
            List<SpellAbility> l = new ArrayList<>();
            for (SpellAbility sa : castable(ai)) {
                Player prev = sa.getActivatingPlayer();
                sa.setActivatingPlayer(ai);
                try { if ("instant".equals(classify(sa, ai)) && sa.getHostCard().isInZone(ZoneType.Hand) && !tried(ai, sa)) l.add(sa); }
                finally { sa.setActivatingPlayer(prev); }
            }
            if (l.isEmpty()) return null;
            Plan p = new Plan();
            p.sa = null; p.why = "hold:end-step";
            p.stockAim = true;
            HELD.set(l);
            return p;
        }
        // removal rule sets
        if (w.myTurn && w.step.isMain() && w.stackEmpty) {
            List<Object[]> th = threats(ai);
            if (th.isEmpty()) return null;
            Player top = (Player) th.get(0)[0];
            List<Card> tg = new ArrayList<>();
            for (Card c : top.getCardsIn(ZoneType.Battlefield)) if (!c.isLand() && (c.isCreature() || c.isPlaneswalker() || c.isArtifact() || c.isEnchantment() || c.isBattle())) tg.add(c);
            return pickRemoval(ai, "removal-sorcery", tg, "sorcery:top-threat");
        }
        if (w.myTurn || !w.stackEmpty) return null;
        if (r == RCOMBAT) {
            if (w.step == PhaseType.COMBAT_DECLARE_ATTACKERS && !w.atMe.isEmpty()) return pickRemoval(ai, "removal-instant", w.atMe, "combat:attacker");
            if (w.step == PhaseType.COMBAT_DECLARE_BLOCKERS && !w.unblockedAtMe.isEmpty()) return pickRemoval(ai, "removal-instant", w.unblockedAtMe, "combat:unblocked");
            return null;
        }
        if (r == RPRE) {
            if (w.step != PhaseType.COMBAT_BEGIN || w.active == null) return null;
            String pred = predictsAttack(ai, w.active);
            if (pred == null) return null;
            List<Card> tg = new ArrayList<>();
            for (Card c : w.active.getCreaturesInPlay()) if (c.getNetCombatDamage() > 0 && CombatUtil.canAttack(c, ai)) tg.add(c);
            if (tg.isEmpty()) return null;
            return pickRemoval(ai, "removal-instant", tg, "precombat:" + pred);
        }
        return null;
    }

    private static final ThreadLocal<List<SpellAbility>> HELD = new ThreadLocal<>();

    /**
     * The planner's acting step (observe, before the seat's own decision): the play to make now under the seat's rule
     * set, its target set, or null (the seat decides as usual, through {@link #filter}).
     */
    static SpellAbility act(Player ai, String r) {
        if (r == OFF || r == BASELINE) return null;
        final Plan p;
        try {
            p = LearnedHooks.observing(ai, true, () -> plan(ai, r));
        } catch (RuntimeException e) {
            ManaView.errorNote("castrules plan", e);
            return null;
        }
        if (p == null) return null;
        final AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        if (p.stockAim) {   // hold-instants at the end-step window: stock's own approval aims; card flow is cast anyway
            List<SpellAbility> held = HELD.get();
            HELD.remove();
            if (held == null) return null;
            held.sort((x, y) -> Integer.compare(ManaView.manaCost(y).getCMC(), ManaView.manaCost(x).getCMC()));   // most mana first
            for (SpellAbility sa : held) {
                sa.setActivatingPlayer(ai);
                AiPlayDecision d;
                Game game = ai.getGame();
                sa.setLastStateBattlefield(game.getLastStateBattlefield());
                sa.setLastStateGraveyard(game.getLastStateGraveyard());
                try {
                    d = aic.canPlaySa(sa);
                } catch (RuntimeException e) {
                    d = AiPlayDecision.CantPlayAi;
                } finally {
                    sa.clearLastState();
                }
                boolean ok = d == AiPlayDecision.WillPlay;
                String why = "hold:end-step";
                if (!ok && !chainTargets(sa) && FORCE_APIS.contains(sa.getApi())) { sa.resetTargets(); ok = true; why = "hold:end-step-forced"; }
                if (!ok) continue;
                if (!ComputerUtilCost.canPayCost(sa, ai, false)) { sa.resetTargets(); continue; }
                noteTry(ai, sa);
                notePlan(ai, sa, why);
                return sa;
            }
            return null;
        }
        SpellAbility sa = p.sa;
        sa.setActivatingPlayer(ai);
        sa.resetTargets();
        if (!sa.canTarget(p.target)) return null;
        sa.getTargets().add(p.target);
        noteTry(ai, sa);
        if (!sa.isTargetNumberValid() || !sa.canPlay() || !ComputerUtilCost.canPayCost(sa, ai, false)) { sa.resetTargets(); return null; }
        notePlan(ai, sa, p.why);
        return sa;
    }

    static boolean chainTargets(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) if (s.usesTargeting()) return true;
        return false;
    }

    private static final ThreadLocal<String> PLAN_WHY = new ThreadLocal<>();

    private static void notePlan(Player ai, SpellAbility sa, String why) {
        PLAN_WHY.set(why);
        synchronized (LearnedCastRules.class) { counts[0]++; }
    }

    // ---- per-priority bookkeeping -------------------------------------------------------------------------------------
    /** every priority of a rules seat (before its decision): attack history, pending outcomes, records due */
    static void tick(Player ai) {
        Game game = ai.getGame();
        Combat c = game.getCombat();
        if (c != null && !c.getAttackers().isEmpty()) {
            int mask = 0;
            for (Card a : c.getAttackers()) {
                Player d = c.getDefenderPlayerByAttacker(a);
                if (d != null) mask |= 1 << LearnedHooks.seatNumber(d);
            }
            synchronized (LearnedCastRules.class) { LAST_ATTACK.put(LearnedHooks.seatNumber(c.getAttackingPlayer()), new int[] {game.getPhaseHandler().getTurn(), mask}); }
        }
        if (!LearnLog.enabled()) return;
        List<Pending> mine = new ArrayList<>();
        synchronized (LearnedCastRules.class) { for (Pending p : PENDING) if (p.ai == ai) mine.add(p); }
        if (mine.isEmpty()) return;
        final PhaseHandler ph = game.getPhaseHandler();
        final int turn = ph.getTurn();
        final boolean myTurn = ph.getPlayerTurn() == ai;
        for (Pending p : mine) {
            try {
                if (turn == p.turn) {
                    p.idle = LearnedHooks.observing(ai, true, () -> ManaView.untapped(ai));
                    for (Tgt t : p.tgts) if (t.card != null && "attacker".equals(t.role)) t.dmgToMe = combatDamageTo(t.card, ai);
                } else if (myTurn) {
                    String body = LearnedHooks.observing(ai, true, () -> p.finish(true));
                    synchronized (LearnedCastRules.class) { PENDING.remove(p); counts[2]++; }
                    LearnLog.raw(HOOK, body);
                }
            } catch (RuntimeException e) {
                ManaView.errorNote("cast_util tick", e);
                synchronized (LearnedCastRules.class) { PENDING.remove(p); counts[3]++; }
            }
        }
    }

    /** combat damage {@code src} dealt to {@code p} this turn (its own damage history; cleared at the next turn) */
    static int combatDamageTo(Card src, Player p) {
        int sum = 0;
        for (Pair<Integer, Boolean> d : src.getDamageHistory().getAllDmgInstances()) {
            if (!d.getRight()) continue;
            Pair<Card, GameEntity> lki = p.getGame().getDamageLKI(d);
            if (lki != null && lki.getRight() instanceof Player && ((Player) lki.getRight()).getId() == p.getId()) sum += d.getLeft();
        }
        return sum;
    }

    /** LearnLog.endGame: pending records of the finished game, outcome as far as it got ("done":0) */
    static void flush() {
        List<Pending> all;
        synchronized (LearnedCastRules.class) { all = new ArrayList<>(PENDING); PENDING.clear(); }
        for (Pending p : all) {
            try {
                LearnLog.raw(HOOK, p.finish(false));
                synchronized (LearnedCastRules.class) { counts[2]++; }
            } catch (RuntimeException e) {
                ManaView.errorNote("cast_util flush", e);
            }
        }
    }

    // ---- cast_util records -------------------------------------------------------------------------------------------
    /** what the seat meant to cast at this priority, with the board roles the record needs (built from a saved state) */
    static final class Intent {
        Player ai; int saId, hostId; String head; String rules, cls, pilot, why;
        Set<Integer> attackers = new HashSet<>();
        Player top; int topSeat; Set<Integer> topCards = new HashSet<>();
    }

    static final class Tgt {
        String kind, role, name; int id, ctrl, creature, pow, tough, attacksAtCast; Card card; Integer dmgToMe;
    }

    final static class Pending {
        Player ai; String head; int turn, lifeMe, lifeTop; Player top; Integer idle;
        List<Tgt> tgts = new ArrayList<>();

        boolean onBattlefield(Card c) {
            for (Card b : c.getGame().getCardsIn(ZoneType.Battlefield)) if (b.getId() == c.getId()) return true;
            return false;
        }

        String finish(boolean done) {
            StringBuilder sb = new StringBuilder(head.length() + 512);
            sb.append(head);
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            sb.append(",\"out\":{\"turn\":").append(ph.getTurn()).append(",\"done\":").append(done ? 1 : 0);
            sb.append(",\"idleMana\":").append(idle == null ? "null" : idle.toString());
            sb.append(",\"lifeMe\":").append(ai.getLife() - lifeMe);
            sb.append(",\"lifeTop\":").append(top == null ? "null" : Integer.toString(top.getLife() - lifeTop));
            sb.append(",\"topAlive\":").append(top == null ? "null" : top.isInGame() ? "1" : "0");
            sb.append(",\"tgt\":[");
            int nAtt = 0, pow = 0, dmg = 0, stopped = 0, removed = 0;
            boolean first = true;
            for (Tgt t : tgts) {
                if (!first) sb.append(','); first = false;
                sb.append("{\"id\":").append(t.id).append(",\"role\":\"").append(t.role).append('"');
                if (t.card != null) {
                    boolean dead = !onBattlefield(t.card);
                    int attacks = t.card.getDamageHistory().getAttacksThisGame() - t.attacksAtCast;
                    sb.append(",\"dead\":").append(dead ? 1 : 0).append(",\"attacks\":").append(attacks);
                    if (t.creature == 1 && !"attacker".equals(t.role)) sb.append(",\"deadBeforeAttack\":").append(dead && attacks == 0 ? 1 : 0);
                    if ("attacker".equals(t.role)) {
                        sb.append(",\"dmgToMe\":").append(t.dmgToMe == null ? "null" : t.dmgToMe.toString());
                        nAtt++; pow += t.pow; dmg += t.dmgToMe == null ? 0 : t.dmgToMe;
                        if (t.dmgToMe != null && t.dmgToMe == 0 && t.pow > 0) stopped++;
                    }
                    if (dead && t.ctrl != LearnedHooks.seatNumber(ai)) removed++;
                }
                sb.append('}');
            }
            sb.append(']');
            sb.append(",\"attackStopped\":");
            if (nAtt == 0) sb.append("null");
            else sb.append("{\"n\":").append(nAtt).append(",\"pow\":").append(pow).append(",\"dmg\":").append(dmg).append(",\"stopped\":").append(stopped).append('}');
            sb.append(",\"traded\":{\"spent\":1,\"removed\":").append(removed).append("}}");
            return sb.toString();
        }
    }

    /**
     * observe, after the decision: remember what the seat is about to cast (a spell, not a land or ability), so the
     * cast observer can open its cast_util record. Logging only; from a saved state.
     */
    static void noteDecision(Player ai, String r, List<SpellAbility> result, boolean planned) {
        final String why = PLAN_WHY.get();
        PLAN_WHY.remove();
        if (!LearnLog.enabled()) return;
        final SpellAbility sa = result == null || result.isEmpty() ? null : result.get(0);
        final int seat = LearnedHooks.seatNumber(ai);
        if (sa == null || !sa.isSpell() || sa.isLandAbility()) {
            synchronized (LearnedCastRules.class) { INTENT.remove(seat); }
            return;
        }
        try {
            Intent in = LearnedHooks.observing(ai, true, () -> intent(ai, r, sa, planned ? "planner" : "stock", why));
            synchronized (LearnedCastRules.class) { INTENT.put(seat, in); }
        } catch (RuntimeException e) {
            ManaView.errorNote("cast_util intent", e);
            synchronized (LearnedCastRules.class) { counts[3]++; }
        }
    }

    /** the record head (window, class, mana, threat ranking) and the role lookup; call from a saved state */
    static Intent intent(Player ai, String r, SpellAbility sa, String pilot, String why) {
        Intent in = new Intent();
        in.ai = ai; in.saId = sa.getId(); in.rules = r; in.pilot = pilot; in.why = why;
        Card h = sa.getHostCard();
        in.hostId = h == null ? -1 : h.getId();
        Player prev = sa.getActivatingPlayer();
        sa.setActivatingPlayer(ai);
        try {
            in.cls = classify(sa, ai);
        } finally { if (prev != null) sa.setActivatingPlayer(prev); }
        Window w = window(ai);
        for (Card a : w.atMe) in.attackers.add(a.getId());
        List<Object[]> th = threats(ai);
        if (!th.isEmpty()) {
            in.top = (Player) th.get(0)[0];
            in.topSeat = LearnedHooks.seatNumber(in.top);
            for (Card c : in.top.getCardsIn(ZoneType.Battlefield)) in.topCards.add(c.getId());
        }
        Game game = ai.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        StringBuilder sb = new StringBuilder(512);
        sb.append("\"seat\":").append(LearnedHooks.seatNumber(ai)).append(",\"turn\":").append(ph.getTurn());
        sb.append(",\"phase\":").append(w.step == null ? 0 : w.step.ordinal());
        sb.append(",\"step\":\"").append(w.step == null ? "" : w.step.name()).append('"');
        sb.append(",\"active\":").append(w.active == null ? 0 : LearnedHooks.seatNumber(w.active));
        sb.append(",\"myTurn\":").append(w.myTurn ? 1 : 0).append(",\"nextIsMe\":").append(ph.getNextTurn() == ai ? 1 : 0);
        sb.append(",\"combat\":").append(w.combat ? 1 : 0).append(",\"stack\":").append(w.stackN);
        sb.append(",\"attackedN\":").append(w.atMe.size());
        sb.append(",\"rules\":\"").append(r).append("\",\"cls\":\"").append(in.cls).append('"');
        String rk = removalKind(sa);
        sb.append(",\"removal\":"); ManaView.str(sb, rk);
        boolean pl = false;
        if (rk != null) { try { pl = plannable(sa); } catch (RuntimeException ignored) {} }
        sb.append(",\"plan\":").append(pl ? 1 : 0);
        sb.append(",\"pilot\":\"").append(pilot).append("\",\"why\":"); ManaView.str(sb, why);
        sb.append(",\"card\":"); ManaView.str(sb, h == null ? null : h.getName());
        sb.append(",\"cardId\":").append(in.hostId);
        sb.append(",\"api\":"); ManaView.str(sb, sa.getApi() == null ? null : sa.getApi().name());
        sb.append(",\"cmc\":").append(ManaView.manaCost(sa).getCMC());
        sb.append(",\"manaOpen\":").append(ManaView.untapped(ai));
        sb.append(",\"life\":").append(ai.getLife());
        sb.append(",\"threat\":[");
        for (int i = 0; i < th.size(); i++) {
            if (i > 0) sb.append(',');
            Player o = (Player) th.get(i)[0];
            sb.append("{\"seat\":").append(LearnedHooks.seatNumber(o)).append(",\"score\":").append(th.get(i)[1]).append(",\"life\":").append(o.getLife()).append('}');
        }
        sb.append("],\"top\":").append(in.top == null ? 0 : in.topSeat);
        in.head = sb.toString();
        return in;
    }

    /** MagicStack cast observer (via ManaView.onCast; logging on): opens the cast_util record of an intended cast */
    static void onCast(SpellAbility sp) {
        Player p = sp.getActivatingPlayer();
        if (p == null || sp.isTrigger() || sp.isCopied() || !sp.isSpell()) return;
        final Intent in;
        synchronized (LearnedCastRules.class) {
            if (INTENT.isEmpty()) return;
            int seat = LearnedHooks.seatNumber(p);
            Intent i = INTENT.get(seat);
            Card h = sp.getHostCard();
            if (i == null || i.ai != p || h == null || i.hostId != h.getId()) return;
            INTENT.remove(seat);
            in = i;
        }
        try {
            Pending pd = pending(in, sp);
            synchronized (LearnedCastRules.class) { PENDING.add(pd); }
        } catch (RuntimeException e) {
            ManaView.errorNote("cast_util open", e);
            synchronized (LearnedCastRules.class) { counts[3]++; }
        }
    }

    /** the record as it stands at the cast: targets with their role and state (public information only) */
    static Pending pending(Intent in, SpellAbility sp) {
        Player ai = in.ai;
        Pending pd = new Pending();
        pd.ai = ai; pd.turn = ai.getGame().getPhaseHandler().getTurn(); pd.lifeMe = ai.getLife();
        pd.top = in.top; pd.lifeTop = in.top == null ? 0 : in.top.getLife();
        StringBuilder sb = new StringBuilder(in.head.length() + 256);
        sb.append(in.head).append(",\"targets\":[");
        boolean first = true;
        int mySeat = LearnedHooks.seatNumber(ai);
        for (SpellAbility s = sp; s != null; s = s.getSubAbility()) {
            if (!s.usesTargeting() || s.getTargets() == null) continue;
            for (GameObject o : s.getTargets()) {
                Tgt t = new Tgt();
                if (o instanceof Card) {
                    Card c = (Card) o;
                    t.kind = c.isPlaneswalker() ? "pw" : "card"; t.id = c.getId(); t.ctrl = LearnedHooks.seatNumber(c.getController());
                    boolean hidden = c.isFaceDown() && c.getController() != ai || (c.getZone() != null && c.getZone().getZoneType().isHidden() && c.getOwner() != ai);
                    t.name = hidden ? null : c.getName();
                    t.creature = c.isCreature() ? 1 : 0;
                    t.pow = c.isCreature() ? c.getNetCombatDamage() : 0;
                    t.tough = c.isCreature() ? c.getNetToughness() : 0;
                    t.attacksAtCast = c.getDamageHistory().getAttacksThisGame();
                    t.card = c;
                    t.role = in.attackers.contains(c.getId()) ? "attacker" : t.ctrl == mySeat ? "own"
                            : in.topCards.contains(c.getId()) ? "topThreat" : "other";
                } else if (o instanceof Player) {
                    Player tp = (Player) o;
                    t.kind = "player"; t.id = tp.getId(); t.ctrl = LearnedHooks.seatNumber(tp); t.name = tp.getName();
                    t.role = tp == ai ? "own" : tp == in.top ? "topThreat" : "other";
                    for (int a : in.attackers) for (Card c : tp.getCardsIn(ZoneType.Battlefield)) if (c.getId() == a) t.role = "attackerCtrl";
                } else if (o instanceof SpellAbility) {
                    SpellAbility ts = (SpellAbility) o;
                    Card c = ts.getHostCard();
                    t.kind = "spell"; t.id = c == null ? -1 : c.getId(); t.role = "spell";
                    t.ctrl = ts.getActivatingPlayer() == null ? 0 : LearnedHooks.seatNumber(ts.getActivatingPlayer());
                    t.name = c == null || (c.isFaceDown() && c.getController() != ai) ? null : c.getName();
                } else continue;
                pd.tgts.add(t);
                if (!first) sb.append(','); first = false;
                sb.append("{\"kind\":\"").append(t.kind).append("\",\"id\":").append(t.id).append(",\"ctrl\":").append(t.ctrl);
                sb.append(",\"name\":"); ManaView.str(sb, t.name);
                sb.append(",\"role\":\"").append(t.role).append('"');
                if (t.creature == 1) sb.append(",\"pow\":").append(t.pow).append(",\"tough\":").append(t.tough);
                sb.append('}');
            }
        }
        sb.append(']');
        pd.head = sb.toString();
        return pd;
    }

    /** test / guard helper: the cast_util record of casting {@code sa} now, as it would be written at once */
    static String preview(Player ai, String r, SpellAbility sa) {
        Intent in = intent(ai, r, sa, "stock", null);
        return pending(in, sa).finish(false);
    }

    /** for tests */
    static synchronized int pendingCount() { return PENDING.size(); }
}
