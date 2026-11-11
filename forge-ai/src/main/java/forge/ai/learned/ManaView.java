package forge.ai.learned;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.cost.Cost;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * learned-ai schema 5 (D33): what the acting seat knows about its own mana and hand, for the Resource solver and the
 * Sequencing model. D39 rule: only the seat's own hand plus public state (its battlefield, its mana pool) is read;
 * nothing here looks at another player's hand or any library.
 * <p>
 * Produces: the {@code mana} snapshot, own {@code hand} names and castability for spell_choice records; the appended
 * candidate features ({@link Features#SPELL_EXTRA}); the raw records {@code turn_end} (cleanup step, every seat),
 * {@code mana_payment} (every spell/ability put on the stack, from the engine's own payment) and {@code mana_lost}
 * (floating mana emptied at a step change). Engine checks (canPlay / payability) run from a saved state
 * ({@link LearnedHooks#observing}) so logging never changes play.
 */
public final class ManaView {
    /** colour order of every per-colour array: W U B R G C */
    static final String COLS = "WUBRGC";
    private static final String[] COL1 = {"W", "U", "B", "R", "G", "C"};

    private ManaView() {}

    // ---- per-game state (reset by LearnLog.beginGame) --------------------------------------------------------------
    /** mana lost this turn per player id: [total, W, U, B, R, G, C] */
    private static final Map<Integer, int[]> LOST = new HashMap<>();
    /** spell_choice records this turn per player id: [turn, count] */
    private static final Map<Integer, int[]> DECISIONS = new HashMap<>();
    private static int lostTurn = -1, endTurn = -1;
    private static Game endGame;
    private static int errors;

    static synchronized void resetGame() {
        LOST.clear(); DECISIONS.clear(); lostTurn = -1; endTurn = -1; endGame = null;
    }

    static synchronized void noteDecision(Player p) {
        int turn = p.getGame().getPhaseHandler().getTurn();
        int[] d = DECISIONS.computeIfAbsent(p.getId(), k -> new int[2]);
        if (d[0] != turn) { d[0] = turn; d[1] = 0; }
        d[1]++;
    }

    /** learned-ai D33: the planner's record errors share the cap */
    static void errorNote(String what, Throwable e) { error(what, e); }

    private static void error(String what, Throwable e) {
        if (errors++ < 20) System.out.println("SIMNOTE learned record error " + what + ": " + e);
    }

    // ---- sources and snapshot ---------------------------------------------------------------------------------------
    /** an untapped (usable now) mana source on the battlefield */
    static final class Src {
        /** amount = net mana of its best ability now (produced - mana paid to activate); fc = that activation mana */
        int id, amount, fc; String name, kind; final boolean[] col = new boolean[6];
    }

    static int colIndex(byte manaColor) {
        for (int k = 0; k < ManaAtom.MANATYPES.length; k++) if (ManaAtom.MANATYPES[k] == manaColor) return k;
        return 5;
    }

    /** battlefield permanents of {@code p} with an AI-usable mana ability that can be activated now (untapped, not sick) */
    static List<Src> sources(Player p) {
        List<Src> out = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            List<SpellAbility> mas = ComputerUtilMana.getAIPlayableMana(c);
            if (mas.isEmpty()) continue;
            Src s = null;
            for (SpellAbility ma : mas) {
                ma.setActivatingPlayer(p);
                if (!ma.canPlay()) continue;
                if (s == null) { s = new Src(); s.id = c.getId(); s.name = c.getName(); }
                int produced = 0;
                for (SpellAbility part = ma; part != null; part = part.getSubAbility()) produced += part.amountOfManaGenerated(true);
                produced = Math.max(1, produced);
                int act = ma.getPayCosts() != null && ma.getPayCosts().hasManaCost() ? ma.getPayCosts().getTotalMana().getCMC() : 0;
                if (produced - act > s.amount || (s.amount == 0 && s.fc == 0)) { s.amount = Math.max(0, produced - act); s.fc = act; }
                AbilityManaPart mp = ma.getManaPart();
                if (mp != null) for (int k = 0; k < 6; k++) if (!s.col[k] && mp.canProduce(COL1[k], ma)) s.col[k] = true;
            }
            if (s == null) continue;
            s.kind = c.isLand() ? "land" : c.isCreature() ? "dork" : c.isArtifact() ? "rock" : "other";
            out.add(s);
        }
        return out;
    }

    static int[] pool(Player p) {
        int[] a = new int[6];
        for (int k = 0; k < 6; k++) a[k] = p.getManaPool().getAmountOfColor(ManaAtom.MANATYPES[k]);
        return a;
    }

    /** untapped usable mana (engine estimate, checkPlayable; includes floating mana) */
    static int untapped(Player p) { return ComputerUtilMana.getAvailableManaEstimate(p, true); }

    /** {@code {"untapped":..,"capacity":..,"open":[WUBRGC],"pool":[WUBRGC],"poolTotal":..,"landPlay":0|1,"srcs":[..]}} */
    static void manaJson(StringBuilder sb, Player p) {
        List<Src> srcs = sources(p);
        int[] open = new int[6];
        for (Src s : srcs) for (int k = 0; k < 6; k++) if (s.col[k]) open[k] += s.amount;
        int[] pool = pool(p);
        sb.append("{\"untapped\":").append(untapped(p)).append(",\"capacity\":").append(ComputerUtilMana.getAvailableManaEstimate(p, false));
        sb.append(",\"open\":"); ints(sb, open);
        sb.append(",\"pool\":"); ints(sb, pool);
        sb.append(",\"poolTotal\":").append(p.getManaPool().totalMana());
        sb.append(",\"landPlay\":").append(p.getMaxLandPlaysInfinite() || p.getLandsPlayedThisTurn() < p.getMaxLandPlays() ? 1 : 0);
        sb.append(",\"srcs\":[");
        for (int i = 0; i < srcs.size(); i++) {
            Src s = srcs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(s.id).append(",\"n\":"); str(sb, s.name);
            sb.append(",\"c\":\""); for (int k = 0; k < 6; k++) if (s.col[k]) sb.append(COLS.charAt(k));
            sb.append("\",\"a\":").append(s.amount);
            if (s.fc > 0) sb.append(",\"fc\":").append(s.fc);
            sb.append(",\"k\":\"").append(s.kind).append("\"}");
        }
        sb.append("]}");
    }

    // ---- castability ------------------------------------------------------------------------------------------------
    /** the engine's payability check for the mana cost (no timing) */
    static boolean payable(SpellAbility sa, Player p) {
        sa.setActivatingPlayer(p);
        return ComputerUtilMana.canPayManaCost(sa, p, 0, false);
    }

    /** castable right now: timing / zone restrictions (canPlay) and payable */
    static boolean castableNow(SpellAbility sa, Player p) {
        sa.setActivatingPlayer(p);
        return sa.canPlay() && ComputerUtilMana.canPayManaCost(sa, p, 0, false);
    }

    /** [castable now (any spell of the card), payable ignoring timing, instant speed] for a non-land card in own hand */
    private static boolean[] handCard(Card c, Player p, boolean wantNow) {
        boolean now = false, pay = false, inst = false;
        for (SpellAbility sa : c.getSpells()) {
            try {
                boolean i = instantSpeed(sa, p);
                if (wantNow && !now && castableNow(sa, p)) { now = true; pay = true; inst |= i; continue; }
                if (payable(sa, p)) { pay = true; inst |= i; }
            } catch (RuntimeException e) {
                error("castable " + c.getName(), e);
            }
        }
        return new boolean[] {now, pay, inst};
    }

    static boolean instantSpeed(SpellAbility sa, Player p) {
        Card c = sa.getHostCard();
        if (sa.isPwAbility()) return false;
        if (sa.isSpell()) return c != null && (c.getType().isInstant() || c.hasKeyword(Keyword.FLASH) || sa.withFlash(c, p));
        return sa.getRestrictions() == null || !sa.getRestrictions().isSorcerySpeed();
    }

    // ---- candidate cost and effect ----------------------------------------------------------------------------------
    static ManaCost manaCost(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        return cost != null && cost.hasManaCost() ? cost.getTotalMana() : ManaCost.ZERO;
    }

    /** pips [W, U, B, R, G, C, generic]; hybrid shards split evenly over their colours; X counts nothing */
    static float[] pips(ManaCost mc) {
        float[] p = new float[7];
        p[6] = Math.max(0, mc.getGenericCost());
        for (ManaCostShard s : mc) {
            if (s == ManaCostShard.X) continue;
            int mask = s.getColorMask();
            int bits = Integer.bitCount(mask & 0x1f);
            if (bits == 0) { if (s.isColorless()) p[5] += 1; else p[6] += 1; continue; }
            for (int k = 0; k < 5; k++) if ((mask & (1 << k)) != 0) p[k] += 1f / bits;
        }
        return p;
    }

    /**
     * Creatures (own, opponents') an approved play would remove, estimated from the public board: sweepers
     * (DestroyAll / DamageAll lethal / SacrificeAll / ChangeZoneAll from the battlefield, via the engine's ValidCards
     * match) and spot removal (Destroy / DealDamage / ChangeZone from the battlefield: the chosen creature targets;
     * Sacrifice edicts: one creature per affected player). The whole sub-ability chain is counted.
     */
    static int[] affected(Player acting, SpellAbility root) {
        int own = 0, opp = 0;
        Game game = acting.getGame();
        for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
            ApiType api = s.getApi();
            if (api == null) continue;
            Card host = s.getHostCard();
            switch (api) {
                case DestroyAll: case DamageAll: case SacrificeAll: case ChangeZoneAll: {
                    if (api == ApiType.ChangeZoneAll && !s.getParamOrDefault("Origin", "").contains("Battlefield")) break;
                    String valid = s.getParamOrDefault(api == ApiType.ChangeZoneAll ? "ChangeType" : "ValidCards", "");
                    if (valid.isEmpty()) break;
                    int dmg = api == ApiType.DamageAll && s.hasParam("NumDmg") ? AbilityUtils.calculateAmount(host, s.getParam("NumDmg"), s) : 0;
                    CardCollection hit = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), valid, acting, host, s);
                    for (Card c : hit) {
                        if (!c.isCreature()) continue;
                        if ((api == ApiType.DestroyAll || api == ApiType.DamageAll) && c.hasKeyword(Keyword.INDESTRUCTIBLE)) continue;
                        if (api == ApiType.DamageAll && c.getLethalDamage() > dmg) continue;
                        if (c.getController() == acting) own++; else opp++;
                    }
                    break;
                }
                case Destroy: case DealDamage: case ChangeZone: {
                    if (api == ApiType.ChangeZone && !s.getParamOrDefault("Origin", "").contains("Battlefield")) break;
                    if (!s.usesTargeting() || s.getTargets() == null) break;
                    for (Card c : s.getTargets().getTargetCards()) {
                        if (!c.isCreature()) continue;
                        if (c.getController() == acting) own++; else opp++;
                    }
                    break;
                }
                case Sacrifice: {
                    String what = s.getParamOrDefault("SacValid", "Card");
                    if (!what.startsWith("Creature")) break;
                    List<Player> who = new ArrayList<>();
                    if (s.usesTargeting() && s.getTargets() != null) { for (Player tp : s.getTargets().getTargetPlayers()) who.add(tp); }
                    else if (s.hasParam("Defined")) who.addAll(AbilityUtils.getDefinedPlayers(host, s.getParam("Defined"), s));
                    for (Player p : who) {
                        if (p.getCreaturesInPlay().isEmpty()) continue;
                        if (p == acting) own++; else opp++;
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return new int[] {own, opp};
    }

    // ---- spell_choice: appended features and record fields ----------------------------------------------------------
    /** Result of {@link #spell}: the appended candidate features (pass last) and, when logging, the record fragment. */
    static final class Spell {
        float[][] extra;
        String json;
    }

    /**
     * Appended features for each approved play and the pass candidate, plus (when {@code record}) the spell_choice
     * fields {@code mana}, {@code hand}, {@code handCastable}, {@code castable}, {@code candMana}, {@code candInfo} (schema 8, see {@link #candJson}). Call from a saved state.
     */
    static Spell spell(Player ai, List<SpellAbility> approved, boolean record) {
        Spell out = new Spell();
        int n = approved.size() + 1;
        out.extra = new float[n][];
        int untapped = untapped(ai);
        StringBuilder cm = record ? new StringBuilder(256) : null;
        StringBuilder cd = record ? new StringBuilder(256) : null;   // schema 8: candInfo (identity + targets)
        for (int i = 0; i < approved.size(); i++) {
            SpellAbility sa = approved.get(i);
            boolean now = false, inst = false;
            ManaCost mc = ManaCost.ZERO;
            int[] aff = {0, 0};
            try {
                mc = manaCost(sa);
                inst = instantSpeed(sa, ai);
                now = castableNow(sa, ai);
                aff = affected(ai, sa);
            } catch (RuntimeException e) {
                error("candidate " + sa, e);
            }
            float[] p = pips(mc);
            out.extra[i] = Features.spellCandidateExtra(sa, untapped, mc.getCMC(), now, inst, p, mc.countX() > 0, aff);
            if (cm != null) {
                cm.append(i > 0 ? "," : "").append("{\"cost\":"); str(cm, mc.toString());
                cm.append(",\"cmc\":").append(mc.getCMC()).append(",\"col\":\"");
                for (int k = 0; k < 5; k++) if (p[k] > 0) cm.append(COLS.charAt(k));
                cm.append("\",\"inst\":").append(inst ? 1 : 0).append(",\"now\":").append(now ? 1 : 0);
                Card h = sa.getHostCard();   // printed cost above; a commander also pays tax for its earlier casts
                if (h != null && h.isCommander() && sa.isSpell()) cm.append(",\"cmdCasts\":").append(ai.getCommanderCast(h));
                cm.append('}');
                if (i > 0) cd.append(',');
                try { candJson(cd, ai, sa); } catch (RuntimeException e) { error("cand " + sa, e); cd.append("{}"); }
            }
        }
        out.extra[n - 1] = Features.passCandidateExtra(untapped);
        if (cm == null) return out;
        StringBuilder sb = new StringBuilder(1024);
        sb.append(",\"mana\":"); manaJson(sb, ai);
        List<String> hand = new ArrayList<>(), castable = new ArrayList<>();
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {   // own hand only (D39)
            hand.add(c.getName());
            if (!c.isLand() && handCard(c, ai, true)[0]) castable.add(c.getName());
        }
        sb.append(",\"hand\":"); strs(sb, hand);
        sb.append(",\"handCastable\":").append(castable.size());
        sb.append(",\"castable\":"); strs(sb, castable);
        sb.append(",\"candMana\":[").append(cm).append(approved.isEmpty() ? "" : ",").append("null]");
        sb.append(",\"candInfo\":[").append(cd).append(approved.isEmpty() ? "" : ",").append("null]");
        out.json = sb.toString();
        return out;
    }

    // ---- spell_choice: candidate identity and chosen targets (schema 8) ---------------------------------------------
    /**
     * One {@code candInfo} entry: {@code card} (host name), {@code cardId}, {@code api} (root API), {@code targets}
     * [{name, id, ctrl (seat), kind card|pw|player|spell, api (the targeting (sub-)ability)}] over the whole
     * sub-ability chain plus unchained charm modes, {@code tgtPlayers} (seats, distinct, in order) and {@code modes}
     * (chosen mode descriptions) for a charm. The targets are the acting seat's own intent at decision time (D39 own
     * information); a target card in a hidden zone the seat does not own, or face down, is logged without a name.
     */
    static void candJson(StringBuilder sb, Player ai, SpellAbility sa) {
        Card h = sa.getHostCard();
        sb.append("{\"card\":"); str(sb, h == null ? null : h.getName());
        sb.append(",\"cardId\":").append(h == null ? -1 : h.getId());
        sb.append(",\"api\":"); str(sb, sa.getApi() == null ? null : sa.getApi().name());
        List<SpellAbility> nodes = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) nodes.add(s);
        List<? extends SpellAbility> chosen = sa.getChosenList();
        if (chosen != null && !sa.isSpell()) {   // CharmAi chains the modes only for spells; abilities keep the list
            for (SpellAbility m : chosen) for (SpellAbility s = m; s != null; s = s.getSubAbility()) nodes.add(s);
        }
        List<Integer> seats = new ArrayList<>();
        sb.append(",\"targets\":[");
        boolean first = true;
        for (SpellAbility s : nodes) {
            if (!s.usesTargeting() || s.getTargets() == null) continue;
            String api = s.getApi() == null ? null : s.getApi().name();
            for (Card c : s.getTargets().getTargetCards()) {
                if (!first) sb.append(','); first = false;
                boolean hidden = c.isFaceDown() || (c.getZone() != null && c.getZone().getZoneType().isHidden() && c.getOwner() != ai);
                sb.append("{\"name\":"); str(sb, hidden ? null : c.getName());
                sb.append(",\"id\":").append(c.getId()).append(",\"ctrl\":").append(LearnedHooks.seatNumber(c.getController()));
                sb.append(",\"kind\":\"").append(c.isPlaneswalker() ? "pw" : "card").append("\",\"api\":"); str(sb, api);
                sb.append('}');
            }
            for (Player p : s.getTargets().getTargetPlayers()) {
                if (!first) sb.append(','); first = false;
                int seat = LearnedHooks.seatNumber(p);
                if (!seats.contains(seat)) seats.add(seat);
                sb.append("{\"name\":"); str(sb, p.getName());
                sb.append(",\"id\":").append(p.getId()).append(",\"ctrl\":").append(seat);
                sb.append(",\"kind\":\"player\",\"api\":"); str(sb, api);
                sb.append('}');
            }
            for (SpellAbility t : s.getTargets().getTargetSpells()) {
                if (!first) sb.append(','); first = false;
                Card c = t.getHostCard();
                sb.append("{\"name\":"); str(sb, c == null ? null : c.getName());
                sb.append(",\"id\":").append(c == null ? -1 : c.getId());
                sb.append(",\"ctrl\":").append(t.getActivatingPlayer() == null ? 0 : LearnedHooks.seatNumber(t.getActivatingPlayer()));
                sb.append(",\"kind\":\"spell\",\"api\":"); str(sb, api);
                sb.append('}');
            }
        }
        sb.append("],\"tgtPlayers\":[");
        for (int i = 0; i < seats.size(); i++) { if (i > 0) sb.append(','); sb.append(seats.get(i)); }
        sb.append(']');
        if (sa.getApi() == ApiType.Charm) {
            List<String> modes = new ArrayList<>();
            if (chosen != null) for (SpellAbility m : chosen) modes.add(m.getDescription());
            sb.append(",\"modes\":"); strs(sb, modes);
        }
        sb.append('}');
    }

    // ---- raw records ------------------------------------------------------------------------------------------------
    /** {@code turn_end} body for {@code p} (its own hand only); call from a saved state */
    static String turnEndJson(Player p, int turn, boolean active) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("\"seat\":").append(LearnedHooks.seatNumber(p)).append(",\"turn\":").append(turn).append(",\"active\":").append(active ? 1 : 0);
        sb.append(",\"life\":").append(p.getLife());
        List<String> hand = new ArrayList<>(), castable = new ArrayList<>(), inst = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            hand.add(c.getName());
            if (c.isLand()) continue;
            boolean[] h = handCard(c, p, false);
            if (h[1]) { castable.add(c.getName()); if (h[2]) inst.add(c.getName()); }
        }
        sb.append(",\"hand\":"); strs(sb, hand);
        sb.append(",\"castable\":"); strs(sb, castable);
        sb.append(",\"castableInstant\":"); strs(sb, inst);
        sb.append(",\"mana\":"); manaJson(sb, p);
        int[] lost; int decisions;
        synchronized (ManaView.class) {
            lost = lostTurn == turn ? LOST.get(p.getId()) : null;
            int[] d = DECISIONS.get(p.getId());
            decisions = d != null && d[0] == turn ? d[1] : 0;
        }
        sb.append(",\"floatLost\":").append(lost == null ? 0 : lost[0]);
        sb.append(",\"floatLostCol\":"); ints(sb, lost == null ? new int[6] : java.util.Arrays.copyOfRange(lost, 1, 7));
        sb.append(",\"casts\":").append(p.getSpellsCastThisTurn()).append(",\"landsPlayed\":").append(p.getLandsPlayedThisTurn());
        sb.append(",\"decisions\":").append(decisions);
        return sb.toString();
    }

    /** PhaseHandler observer: the CLEANUP step of {@code active}'s turn began (before the hand-size discard). */
    public static void onCleanup(Player active) {
        if (!LearnLog.enabled()) return;
        Game game = active.getGame();
        if (game.isSimulationCopy()) return;
        int turn = game.getPhaseHandler().getTurn();
        synchronized (ManaView.class) {
            if (endGame == game && endTurn == turn) return;   // cleanup repeats when something happens during it
            endGame = game; endTurn = turn;
        }
        for (Player p : Features.seatsFrom(active)) {
            if (p.hasLost()) continue;
            if (LiveLog.isHuman(p)) continue;   // GUI play: a human seat's hand is never recorded (sims: every seat is AI)
            try {
                String body = LearnedHooks.observing(p, true, () -> turnEndJson(p, turn, p == active));
                LearnLog.raw("turn_end", body);
            } catch (RuntimeException e) {
                error("turn_end", e);
            }
        }
    }

    /** PhaseHandler observer: floating mana emptied at a step change. */
    public static void onManaLost(Player p, List<Mana> lost) {
        if (!LearnLog.enabled() || lost == null || lost.isEmpty()) return;
        Game game = p.getGame();
        if (game.isSimulationCopy()) return;
        int turn = game.getPhaseHandler().getTurn();
        int[] col = new int[6];
        for (Mana m : lost) col[colIndex(m.getColor())]++;
        synchronized (ManaView.class) {
            if (lostTurn != turn) { LOST.clear(); lostTurn = turn; }
            int[] acc = LOST.computeIfAbsent(p.getId(), k -> new int[7]);
            acc[0] += lost.size();
            for (int k = 0; k < 6; k++) acc[k + 1] += col[k];
        }
        PhaseType ph = game.getPhaseHandler().getPhase();
        StringBuilder sb = new StringBuilder(96);
        sb.append("\"seat\":").append(LearnedHooks.seatNumber(p)).append(",\"turn\":").append(turn).append(",\"phase\":").append(ph == null ? 0 : ph.ordinal());
        sb.append(",\"n\":").append(lost.size()).append(",\"col\":"); ints(sb, col);
        LearnLog.raw("mana_lost", sb.toString());
    }

    /**
     * MagicStack observer: {@code sp} was just put on the stack, paid. The payment is the engine's own: every mana spent
     * ({@code SpellAbility.getPayingMana}) with its source; mana not made by an ability activated for this payment came
     * from the floating pool.
     */
    public static void onCast(SpellAbility sp) {
        if (!LearnLog.enabled() || sp == null) return;
        try { LearnedCastRules.onCast(sp); } catch (RuntimeException e) { error("cast_util onCast", e); }   // D44 (schema 11)
        Player p = sp.getActivatingPlayer();
        if (p == null) return;
        Game game = p.getGame();
        if (game.isSimulationCopy()) return;
        if (sp.isTrigger() || sp.isCopied()) return;
        try {
            List<Mana> paid = sp.getPayingMana();
            ManaCost mc = manaCost(sp);
            if (paid.isEmpty() && (mc.isZero() || mc.isNoCost())) return;   // free / no mana cost: nothing to record
            SpellAbility orig = sp.getOriginalAbility() != null ? sp.getOriginalAbility() : sp;
            java.util.Set<Card> tappedFor = new java.util.HashSet<>();
            for (SpellAbility ma : orig.getPayingManaAbilities()) if (ma.getHostCard() != null) tappedFor.add(ma.getHostCard());
            for (SpellAbility ma : sp.getPayingManaAbilities()) if (ma.getHostCard() != null) tappedFor.add(ma.getHostCard());
            // group by source, keeping the payment order
            java.util.LinkedHashMap<String, int[]> bySrc = new java.util.LinkedHashMap<>();
            java.util.Map<String, Card> cards = new HashMap<>();
            java.util.Map<String, StringBuilder> cols = new HashMap<>();
            int fromPool = 0;
            for (Mana m : paid) {
                Card src = m.getSourceCard();
                boolean pool = src == null || !tappedFor.contains(src);
                if (pool) fromPool++;
                String key = (src == null ? "?" : Integer.toString(src.getId())) + (pool ? "p" : "t");
                bySrc.computeIfAbsent(key, k -> new int[] {pool ? 1 : 0});
                cards.put(key, src);
                cols.computeIfAbsent(key, k -> new StringBuilder()).append(COLS.charAt(colIndex(m.getColor())));
            }
            Card host = sp.getHostCard();
            PhaseType ph = game.getPhaseHandler().getPhase();
            StringBuilder sb = new StringBuilder(256);
            sb.append("\"seat\":").append(LearnedHooks.seatNumber(p)).append(",\"turn\":").append(game.getPhaseHandler().getTurn());
            sb.append(",\"phase\":").append(ph == null ? 0 : ph.ordinal());
            sb.append(",\"active\":").append(game.getPhaseHandler().getPlayerTurn() == p ? 1 : 0);
            sb.append(",\"card\":"); str(sb, host == null ? "" : host.getName());
            sb.append(",\"cardId\":").append(host == null ? -1 : host.getId());
            sb.append(",\"kind\":\"").append(sp.isSpell() ? "spell" : sp.isPwAbility() ? "pw" : "ability").append('"');
            sb.append(",\"cost\":"); str(sb, mc.toString());
            if (host != null && host.isCommander() && sp.isSpell()) sb.append(",\"cmdCasts\":").append(p.getCommanderCast(host));
            Integer x = sp.getXManaCostPaid();
            sb.append(",\"x\":").append(x == null ? 0 : x);
            sb.append(",\"paid\":[");
            int i = 0;
            for (Map.Entry<String, int[]> e : bySrc.entrySet()) {
                Card src = cards.get(e.getKey());
                if (i++ > 0) sb.append(',');
                sb.append("{\"id\":").append(src == null ? -1 : src.getId()).append(",\"n\":"); str(sb, src == null ? "" : src.getName());
                sb.append(",\"c\":\"").append(cols.get(e.getKey())).append("\",\"pool\":").append(e.getValue()[0]).append('}');
            }
            sb.append("],\"nPaid\":").append(paid.size()).append(",\"fromPool\":").append(fromPool);
            sb.append(",\"poolAfter\":"); ints(sb, pool(p));
            LearnLog.raw("mana_payment", sb.toString());
        } catch (RuntimeException e) {
            error("mana_payment", e);
        }
    }

    // ---- json helpers -----------------------------------------------------------------------------------------------
    static void ints(StringBuilder sb, int[] a) {
        sb.append('[');
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        sb.append(']');
    }

    static void str(StringBuilder sb, String s) {
        sb.append('"');
        if (s != null) for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
    }

    static void strs(StringBuilder sb, List<String> a) {
        sb.append('[');
        for (int i = 0; i < a.size(); i++) { if (i > 0) sb.append(','); str(sb, a.get(i)); }
        sb.append(']');
    }
}
