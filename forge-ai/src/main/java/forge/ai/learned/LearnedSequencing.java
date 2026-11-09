package forge.ai.learned;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.ai.AiController;
import forge.ai.AiProps;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.cost.CostPayment;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/**
 * learned-ai D33 Sequencing planner, phase 1 (schema 9): observation only. Behind the Learned-profile property
 * {@code LEARNED_SEQUENCING=planner} (default off; {@code -seq} / {@code -Dlearned.sequencing} override every Learned
 * seat), at EVERY priority the seat receives on any player's turn it enumerates its own legal, payable instant-speed
 * plays (instants, flash permanents, activated abilities) with their legal targets, using only the engine's own
 * legality ({@code canPlay}, target restrictions) and payability checks, and only the seat's own information (D39:
 * own hand, graveyard, command zone; public zones; a library top only when the seat may look at it). It writes a
 * {@code seq_choice} record next to what stock (or the shipped spell_choice model) actually does. Phase 1 changes no
 * play: the enumeration and the record come from a saved state ({@link LearnedHooks#observing}), the activating
 * player of every touched ability is put back, and the seat's own decision runs afterwards from the restored state.
 * <p>
 * At the seat's own main phases the record also carries {@code holdPlan}: for the empty portfolio and each one- or
 * two-spell sorcery-speed portfolio castable now, which of the seat's instants in hand could still be paid from the
 * sources left untapped (a greedy colour-aware assignment over {@link ManaView#sources}, an approximation of the
 * resource solver, not the engine's payment).
 */
public final class LearnedSequencing {
    public static final String OFF = "off", PLANNER = "planner";
    public static final String HOOK = "seq_choice";
    /** sim / JVM override of every Learned seat's LEARNED_SEQUENCING (null = profile) */
    public static volatile String override = System.getProperty("learned.sequencing");
    /** cap on legal target options written per candidate (nTgt keeps the full count) */
    static int maxTargets = 40;
    /** cap on sorcery-speed spells considered for hold-plan portfolios (singles and pairs) */
    static int maxPortfolioSpells = 8;

    private LearnedSequencing() {}

    public static void setOverride(String mode) { override = mode; }

    public static String mode(AiController aic) {
        if (!aic.getBoolProperty(AiProps.LEARNED_HOOKS)) return OFF;
        String o = override;
        String m = o != null && !o.trim().isEmpty() ? o.trim() : aic.getProperty(AiProps.LEARNED_SEQUENCING);
        return PLANNER.equalsIgnoreCase(m) ? PLANNER : OFF;
    }

    /** whether the planner observes this seat now: logging on, planner mode, a real (not simulated) game */
    static boolean active(Player ai) {
        if (!LearnLog.enabled()) return false;
        if (!(ai.getController() instanceof PlayerControllerAi)) return false;
        if (ai.getGame().isSimulationCopy()) return false;
        return PLANNER.equals(mode(((PlayerControllerAi) ai.getController()).getAi()));
    }

    // ---- per-game state -------------------------------------------------------------------------------------------
    /** per seat: [priorities without a record since the last record, priorities seen this game, records this game] */
    private static final Map<Integer, long[]> PRIO = new HashMap<>();
    /** per seat: [turn, attacked-this-turn bitmask of attacking seats, attackers at me] remembered from combat priorities */
    private static final Map<Integer, int[]> ATTACKED = new HashMap<>();
    private static long nanos;

    static synchronized void resetGame() { PRIO.clear(); ATTACKED.clear(); nanos = 0; }

    /** captured stock spell_choice list of this priority (set by LearnedHooks.spellChoice while armed) */
    private static final ThreadLocal<List<Object[]>> CAPTURE = new ThreadLocal<>();

    /** LearnedHooks.spellChoice: the approved list as the seat's spell_choice saw it: [saId, hostId, description, candInfo json] */
    static void capture(Player ai, List<SpellAbility> approved) {
        if (CAPTURE.get() == null) return;
        List<Object[]> l = new ArrayList<>();
        for (SpellAbility sa : approved) {
            StringBuilder sb = new StringBuilder();
            try { ManaView.candJson(sb, ai, sa); } catch (RuntimeException e) { sb.setLength(0); sb.append("{}"); }
            Card h = sa.getHostCard();
            l.add(new Object[] {sa.getId(), h == null ? -1 : h.getId(), String.valueOf(sa.getDescription()), sb.toString()});
        }
        CAPTURE.set(l);
    }

    /**
     * PlayerControllerAi.chooseSpellAbilityToPlay: the seat's own decision ({@code decide}) wrapped by the planner's
     * observation. Returns exactly what {@code decide} returns.
     */
    public static List<SpellAbility> observe(Player ai, java.util.function.Supplier<List<SpellAbility>> decide) {
        if (!active(ai)) return decide.get();
        final long t0 = System.nanoTime();
        final Pre pre;
        try {
            pre = LearnedHooks.observing(ai, true, () -> pre(ai));
        } catch (RuntimeException e) {
            ManaView.errorNote("seq_choice pre", e);
            return decide.get();
        }
        final long t1 = System.nanoTime();
        CAPTURE.set(new ArrayList<>());
        List<Object[]> stockList = null;
        List<SpellAbility> result;
        try {
            result = decide.get();
            stockList = CAPTURE.get();
        } finally {
            CAPTURE.remove();
        }
        final long t2 = System.nanoTime();
        final List<Object[]> sl = stockList;
        final List<SpellAbility> res = result;
        try {
            String body = LearnedHooks.observing(ai, true, () -> post(ai, pre, sl, res, (t1 - t0)));
            if (body != null) LearnLog.raw(HOOK, body);
        } catch (RuntimeException e) {
            ManaView.errorNote("seq_choice post", e);
        }
        synchronized (LearnedSequencing.class) { nanos += (t1 - t0) + (System.nanoTime() - t2); }
        return result;
    }

    /** total planner wall time this game (ns), for the overhead report */
    public static synchronized long nanos() { return nanos; }

    // ---- enumeration ----------------------------------------------------------------------------------------------
    /** One enumerated play: the ability and its record entry. */
    static final class Cand {
        SpellAbility sa; int saId, hostId; String desc, json;
    }

    /** What the planner saw before the seat decided. */
    static final class Pre {
        List<Cand> cands = new ArrayList<>();
        String context;   // phase/step/active/attacked/stack/mana/holdPlan fragment
        int seat, turn, phase;
    }

    /** cards whose abilities the seat may know about (D39): own hand/graveyard/command, public zones, a visible library top */
    static CardCollection visibleCards(Player ai) {
        Game game = ai.getGame();
        CardCollection all = new CardCollection(ai.getCardsIn(ZoneType.Hand));
        all.addAll(ai.getCardsIn(ZoneType.Graveyard));
        for (Player p : game.getPlayers()) {
            if (p.getCardsIn(ZoneType.Library).isEmpty()) continue;
            Card top = p.getCardsIn(ZoneType.Library).get(0);
            if (top.mayPlayerLook(ai)) all.add(top);   // only an effect that lets the seat look at it
        }
        for (Card c : ai.getCardsIn(ZoneType.Command)) if (!c.isImmutable() || c.isEmblem()) all.add(c);
        for (Card c : game.getCardsIn(ZoneType.Exile)) if (!c.isFaceDown() || c.getOwner() == ai || c.mayPlayerLook(ai)) all.add(c);
        all.addAll(game.getCardsIn(ZoneType.Battlefield));
        return ComputerUtilCard.dedupeCards(all);
    }

    /** the seat's legal, payable instant-speed plays now (call from a saved state) */
    static List<Cand> enumerate(Player ai) {
        List<Cand> out = new ArrayList<>();
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(visibleCards(ai), ai);
        List<Object[]> activators = new ArrayList<>();
        for (SpellAbility sa : all) activators.add(new Object[] {sa, sa.getActivatingPlayer()});
        try {
            for (SpellAbility sa : all) {
                try {
                    if (sa.isLandAbility() || sa.isManaAbility() || sa.isTrigger()) continue;
                    sa.setActivatingPlayer(ai);
                    if (!ManaView.instantSpeed(sa, ai)) continue;
                    if (!sa.canPlay()) continue;
                    if (!ComputerUtilMana.canPayManaCost(sa, ai, 0, false)) continue;
                    if (!CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false)) continue;
                    StringBuilder sb = new StringBuilder(256);
                    if (!candJson(sb, ai, sa)) continue;   // a required target has no legal choice
                    Cand c = new Cand();
                    c.sa = sa; c.saId = sa.getId(); c.hostId = sa.getHostCard() == null ? -1 : sa.getHostCard().getId();
                    c.desc = String.valueOf(sa.getDescription()); c.json = sb.toString();
                    out.add(c);
                } catch (RuntimeException e) {
                    ManaView.errorNote("seq enumerate " + sa, e);
                }
            }
        } finally {
            for (Object[] a : activators) ((SpellAbility) a[0]).setActivatingPlayer((Player) a[1]);   // as the seat's own pass found them
        }
        return out;
    }

    /**
     * candInfo-shaped entry with the LEGAL target options (not chosen ones): card, cardId, saId, api, kind, zone, cost,
     * cmc, col, flash, x, minTgt, targets [{name,id,ctrl,kind,api}], nTgt, nHidden, modes (charm). False when a node
     * that needs a target has none.
     */
    static boolean candJson(StringBuilder sb, Player ai, SpellAbility sa) {
        Card h = sa.getHostCard();
        ManaCost mc = ManaView.manaCost(sa);
        sb.append("{\"card\":"); ManaView.str(sb, h == null ? null : h.getName());
        sb.append(",\"cardId\":").append(h == null ? -1 : h.getId()).append(",\"saId\":").append(sa.getId());
        sb.append(",\"api\":"); ManaView.str(sb, sa.getApi() == null ? null : sa.getApi().name());
        sb.append(",\"kind\":\"").append(sa.isSpell() ? (h != null && h.isPermanent() ? "permanent" : "spell") : "ability").append('"');
        sb.append(",\"zone\":\"").append(h == null || h.getZone() == null ? "" : h.getZone().getZoneType().name()).append('"');
        sb.append(",\"cost\":"); ManaView.str(sb, mc.toString());
        int tax = h != null && h.isCommander() && sa.isSpell() && h.isInZone(ZoneType.Command) ? 2 * ai.getCommanderCast(h) : 0;
        sb.append(",\"cmc\":").append(mc.getCMC() + tax);
        if (tax > 0) sb.append(",\"tax\":").append(tax);
        float[] p = ManaView.pips(mc);
        sb.append(",\"col\":\""); for (int k = 0; k < 5; k++) if (p[k] > 0) sb.append(ManaView.COLS.charAt(k)); sb.append('"');
        sb.append(",\"flash\":").append(sa.isSpell() && h != null && !h.getType().isInstant() ? 1 : 0);
        sb.append(",\"x\":").append(mc.countX() > 0 ? 1 : 0);
        List<SpellAbility> nodes = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) nodes.add(s);
        List<String> modes = null;
        if (sa.getApi() == ApiType.Charm) {
            modes = new ArrayList<>();
            List<AbilitySub> choices = sa.getAdditionalAbilityList("Choices");
            if (choices != null) for (AbilitySub m : choices) {
                modes.add(String.valueOf(m.getDescription()));
                for (SpellAbility s = m; s != null; s = s.getSubAbility()) nodes.add(s);
            }
        }
        int min = 0, n = 0, hidden = 0;
        boolean ok = true, first = true;
        sb.append(",\"targets\":[");
        for (int ni = 0; ni < nodes.size(); ni++) {
            SpellAbility s = nodes.get(ni);
            if (!s.usesTargeting()) continue;
            TargetRestrictions tr = s.getTargetRestrictions();
            if (tr == null) continue;
            if (s.getActivatingPlayer() == null) s.setActivatingPlayer(ai);
            int nodeMin = tr.getMinTargets(h, s);
            boolean mode = ni >= countChain(sa);   // a charm mode: optional, judged by the charm itself
            if (!mode && ni == 0) min = nodeMin;
            String api = s.getApi() == null ? null : s.getApi().name();
            int found = 0;
            for (GameEntity e : tr.getAllCandidates(s)) {
                if (e instanceof Card) {
                    Card c = (Card) e;
                    if (c.getZone() != null && c.getZone().getZoneType().isHidden() && c.getOwner() != ai && !c.mayPlayerLook(ai)) { hidden++; continue; }
                    found++; n++;
                    if (n > maxTargets) continue;
                    if (!first) sb.append(','); first = false;
                    sb.append("{\"name\":"); ManaView.str(sb, c.isFaceDown() && c.getController() != ai ? null : c.getName());
                    sb.append(",\"id\":").append(c.getId()).append(",\"ctrl\":").append(LearnedHooks.seatNumber(c.getController()));
                    sb.append(",\"kind\":\"").append(c.isPlaneswalker() ? "pw" : "card").append("\",\"api\":"); ManaView.str(sb, api);
                    sb.append('}');
                } else if (e instanceof Player) {
                    Player tp = (Player) e;
                    found++; n++;
                    if (n > maxTargets) continue;
                    if (!first) sb.append(','); first = false;
                    sb.append("{\"name\":"); ManaView.str(sb, tp.getName());
                    sb.append(",\"id\":").append(tp.getId()).append(",\"ctrl\":").append(LearnedHooks.seatNumber(tp));
                    sb.append(",\"kind\":\"player\",\"api\":"); ManaView.str(sb, api);
                    sb.append('}');
                }
            }
            if (tr.getZone() != null && tr.getZone().contains(ZoneType.Stack)) {
                for (SpellAbilityStackInstance si : ai.getGame().getStack()) {
                    SpellAbility t = si.getSpellAbility();
                    if (t == null || !s.canTargetSpellAbility(t)) continue;
                    found++; n++;
                    if (n > maxTargets) continue;
                    Card c = si.getSourceCard();
                    if (!first) sb.append(','); first = false;
                    sb.append("{\"name\":"); ManaView.str(sb, c == null || (c.isFaceDown() && c.getController() != ai) ? null : c.getName());
                    sb.append(",\"id\":").append(c == null ? -1 : c.getId());
                    sb.append(",\"ctrl\":").append(si.getActivatingPlayer() == null ? 0 : LearnedHooks.seatNumber(si.getActivatingPlayer()));
                    sb.append(",\"kind\":\"spell\",\"api\":"); ManaView.str(sb, api);
                    sb.append('}');
                }
            }
            if (!mode && nodeMin > 0 && found < nodeMin) ok = false;
        }
        sb.append("],\"nTgt\":").append(n).append(",\"minTgt\":").append(min);
        if (hidden > 0) sb.append(",\"nHidden\":").append(hidden);
        if (modes != null) { sb.append(",\"modes\":"); ManaView.strs(sb, modes); }
        sb.append('}');
        return ok;
    }

    private static int countChain(SpellAbility sa) {
        int k = 0;
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) k++;
        return k;
    }

    // ---- context --------------------------------------------------------------------------------------------------
    static Pre pre(Player ai) {
        Pre pre = new Pre();
        Game game = ai.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        PhaseType step = ph.getPhase();
        Player active = ph.getPlayerTurn();
        pre.seat = LearnedHooks.seatNumber(ai);
        pre.turn = ph.getTurn();
        pre.phase = step == null ? 0 : step.ordinal();
        pre.cands = enumerate(ai);
        StringBuilder sb = new StringBuilder(1024);
        sb.append(",\"step\":\"").append(step == null ? "" : step.name()).append('"');
        sb.append(",\"active\":").append(active == null ? 0 : LearnedHooks.seatNumber(active));
        sb.append(",\"myTurn\":").append(active == ai ? 1 : 0);
        // attacked this combat: attackers aimed at this seat or at a permanent it controls/protects
        Combat combat = game.getCombat();
        int[] att;
        synchronized (LearnedSequencing.class) {
            att = ATTACKED.computeIfAbsent(pre.seat, k -> new int[3]);
            if (att[0] != pre.turn) { att[0] = pre.turn; att[1] = 0; att[2] = 0; }
        }
        List<String> names = new ArrayList<>();
        int atMe = 0, byMask = 0;
        if (combat != null && combat.getAttackingPlayer() != ai) {
            for (Card a : combat.getAttackers()) {
                if (combat.getDefenderPlayerByAttacker(a) != ai) continue;
                atMe++;
                byMask |= 1 << LearnedHooks.seatNumber(a.getController());
                names.add(a.isFaceDown() ? null : a.getName());
            }
            if (atMe > 0) { att[1] |= byMask; att[2] = Math.max(att[2], atMe); }
        }
        sb.append(",\"combat\":").append(combat != null ? 1 : 0);
        sb.append(",\"attacked\":{\"n\":").append(atMe).append(",\"by\":"); seats(sb, byMask);
        sb.append(",\"names\":"); ManaView.strs(sb, names);
        sb.append(",\"thisTurn\":").append(att[1] != 0 ? 1 : 0).append(",\"byTurn\":"); seats(sb, att[1]);
        sb.append('}');
        sb.append(",\"stack\":[");
        boolean first = true;
        for (SpellAbilityStackInstance si : game.getStack()) {   // top first
            if (!first) sb.append(','); first = false;
            Card c = si.getSourceCard();
            SpellAbility s = si.getSpellAbility();
            sb.append("{\"name\":"); ManaView.str(sb, c == null || (c.isFaceDown() && c.getController() != ai) ? null : c.getName());
            sb.append(",\"seat\":").append(si.getActivatingPlayer() == null ? 0 : LearnedHooks.seatNumber(si.getActivatingPlayer()));
            sb.append(",\"kind\":\"").append(si.isTrigger() ? "trigger" : s != null && s.isSpell() ? "spell" : "ability").append("\",\"api\":");
            ManaView.str(sb, s == null || s.getApi() == null ? null : s.getApi().name());
            sb.append('}');
        }
        sb.append(']');
        sb.append(",\"mana\":"); ManaView.manaJson(sb, ai);
        if (active == ai && game.getStack().isEmpty() && step != null && step.isMain()) holdPlan(sb, ai);
        pre.context = sb.toString();
        return pre;
    }

    private static void seats(StringBuilder sb, int mask) {
        sb.append('[');
        boolean first = true;
        for (int s = 1; s < 31; s++) if ((mask & (1 << s)) != 0) { if (!first) sb.append(','); first = false; sb.append(s); }
        sb.append(']');
    }

    // ---- hold plan --------------------------------------------------------------------------------------------------
    /** a spell in the plan: id, name, cost shards (as colour masks: bit k = WUBRG k, 32 = C, 0 = generic), generic count */
    static final class Need {
        int id; String name; List<Integer> shards = new ArrayList<>(); int generic;
    }

    static Need need(Card c, SpellAbility sa, Player ai) {
        Need n = new Need();
        n.id = c.getId(); n.name = c.getName();
        ManaCost mc = ManaView.manaCost(sa);
        n.generic = Math.max(0, mc.getGenericCost());
        if (c.isCommander() && c.isInZone(ZoneType.Command)) n.generic += 2 * ai.getCommanderCast(c);
        for (ManaCostShard s : mc) {
            if (s == ManaCostShard.X) continue;
            int mask = s.getColorMask() & 0x1f;
            if (mask == 0) { if (s.isColorless()) n.shards.add(32); else n.generic++; continue; }
            n.shards.add(mask);
        }
        return n;
    }

    /**
     * Greedy colour-aware joint payment of {@code needs} from {@code left} (amount per source, mutated; whole-source
     * tapping, so a source's spare mana floats for the rest of the list only): every coloured shard first, the most
     * constrained (fewest producing sources) first, from the least flexible source; then generic mana from sources
     * already tapped, then the least flexible. {@code used} marks tapped sources. An approximation, not the engine.
     */
    static boolean pay(List<Need> needs, List<ManaView.Src> srcs, int[] left, boolean[] used) {
        List<Integer> shards = new ArrayList<>();
        int generic = 0;
        for (Need n : needs) { shards.addAll(n.shards); generic += n.generic; }
        final int[] prod = new int[shards.size()];
        for (int k = 0; k < shards.size(); k++) for (ManaView.Src s : srcs) if (produces(s, shards.get(k))) prod[k]++;
        Integer[] order = new Integer[shards.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        java.util.Arrays.sort(order, (x, y) -> Integer.compare(prod[x], prod[y]));
        for (int k : order) {
            int mask = shards.get(k), best = -1;
            for (int i = 0; i < srcs.size(); i++) {
                if (left[i] <= 0 || !produces(srcs.get(i), mask)) continue;
                if (best < 0 || (used[i] && !used[best]) || (used[i] == used[best] && flex(srcs.get(i)) < flex(srcs.get(best)))) best = i;
            }
            if (best < 0) return false;
            left[best]--; used[best] = true;
        }
        for (int g = 0; g < generic; g++) {
            int best = -1;
            for (int i = 0; i < srcs.size(); i++) {
                if (left[i] <= 0) continue;
                if (best < 0 || (used[i] && !used[best]) || (used[i] == used[best] && flex(srcs.get(i)) < flex(srcs.get(best)))) best = i;
            }
            if (best < 0) return false;
            left[best]--; used[best] = true;
        }
        return true;
    }

    private static boolean produces(ManaView.Src s, int mask) {
        if (mask == 32) return s.col[5];
        for (int k = 0; k < 5; k++) if ((mask & (1 << k)) != 0 && s.col[k]) return true;
        return false;
    }

    private static int flex(ManaView.Src s) {
        int f = 0;
        for (boolean b : s.col) if (b) f++;
        return f;
    }

    /** {@code holdPlan}: instants (and flash cards) in own hand payable from what each sorcery-speed portfolio leaves untapped */
    static void holdPlan(StringBuilder sb, Player ai) {
        List<ManaView.Src> srcs = ManaView.sources(ai);
        List<Need> inst = new ArrayList<>(), sorc = new ArrayList<>();
        List<Card> pool = new ArrayList<>(ai.getCardsIn(ZoneType.Hand));   // own hand only (D39)
        for (Card c : ai.getCardsIn(ZoneType.Command)) if (c.isCommander()) pool.add(c);
        for (Card c : pool) {
            if (c.isLand()) continue;
            boolean isInst = false, isSorc = false;
            SpellAbility instSa = null, sorcSa = null;
            for (SpellAbility sa : c.getSpells()) {
                try {
                    Player prev = sa.getActivatingPlayer();
                    sa.setActivatingPlayer(ai);
                    boolean i = ManaView.instantSpeed(sa, ai);
                    if (i && !isInst && c.isInZone(ZoneType.Hand)) { isInst = true; instSa = sa; }
                    if (!i && !isSorc && sorc.size() < maxPortfolioSpells && ManaView.castableNow(sa, ai)) { isSorc = true; sorcSa = sa; }
                    sa.setActivatingPlayer(prev);
                } catch (RuntimeException e) {
                    ManaView.errorNote("holdPlan " + c.getName(), e);
                }
            }
            if (instSa != null) inst.add(need(c, instSa, ai));
            if (sorcSa != null) sorc.add(need(c, sorcSa, ai));
        }
        sb.append(",\"holdPlan\":{\"instants\":[");
        for (int i = 0; i < inst.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(inst.get(i).id).append(",\"n\":"); ManaView.str(sb, inst.get(i).name); sb.append('}');
        }
        sb.append("],\"portfolios\":[");
        List<int[]> ports = new ArrayList<>();
        ports.add(new int[0]);
        for (int a = 0; a < sorc.size(); a++) ports.add(new int[] {a});
        for (int a = 0; a < sorc.size(); a++) for (int b = a + 1; b < sorc.size(); b++) ports.add(new int[] {a, b});
        boolean firstP = true;
        for (int[] port : ports) {
            List<Need> ps = new ArrayList<>();
            for (int k : port) ps.add(sorc.get(k));
            int[] left = new int[srcs.size()];
            boolean[] used = new boolean[srcs.size()];
            for (int i = 0; i < srcs.size(); i++) left[i] = srcs.get(i).amount;
            boolean ok = pay(ps, srcs, left, used);
            if (!ok && port.length > 1) continue;   // infeasible pairs are not written
            if (!firstP) sb.append(','); firstP = false;
            sb.append("{\"cast\":[");
            for (int k = 0; k < port.length; k++) { if (k > 0) sb.append(','); sb.append(sorc.get(port[k]).id); }
            sb.append("],\"names\":[");
            for (int k = 0; k < port.length; k++) { if (k > 0) sb.append(','); ManaView.str(sb, sorc.get(port[k]).name); }
            sb.append("],\"ok\":").append(ok ? 1 : 0);
            if (ok) {
                sb.append(",\"open\":[");
                boolean f = true;
                for (int i = 0; i < srcs.size(); i++) if (!used[i]) { if (!f) sb.append(','); f = false; sb.append(srcs.get(i).id); }
                sb.append("],\"hold\":[");
                f = true;
                for (Need h : inst) {   // castable later with this portfolio cast now: joint payment of portfolio + instant
                    List<Need> joint = new ArrayList<>(ps);
                    joint.add(h);
                    int[] l2 = new int[srcs.size()];
                    for (int i = 0; i < srcs.size(); i++) l2[i] = srcs.get(i).amount;
                    if (!pay(joint, srcs, l2, new boolean[srcs.size()])) continue;
                    if (!f) sb.append(','); f = false;
                    sb.append(h.id);
                }
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append("]}");
    }

    // ---- record -----------------------------------------------------------------------------------------------------
    static int match(List<Cand> cands, SpellAbility sa) {
        if (sa == null) return -1;
        Card h = sa.getHostCard();
        int hid = h == null ? -1 : h.getId();
        for (int i = 0; i < cands.size(); i++) if (cands.get(i).saId == sa.getId() && cands.get(i).hostId == hid) return i;
        String d = String.valueOf(sa.getDescription());
        for (int i = 0; i < cands.size(); i++) if (cands.get(i).hostId == hid && cands.get(i).desc.equals(d)) return i;
        return -2;
    }

    static int match(List<Cand> cands, Object[] st) {
        for (int i = 0; i < cands.size(); i++) if (cands.get(i).saId == (Integer) st[0] && cands.get(i).hostId == (Integer) st[1]) return i;
        for (int i = 0; i < cands.size(); i++) if (cands.get(i).hostId == (Integer) st[1] && cands.get(i).desc.equals(st[2])) return i;
        return -2;
    }

    /** the record body, or null when nothing was on offer (no candidate, no stock list, the seat passed) */
    static String post(Player ai, Pre pre, List<Object[]> stockList, List<SpellAbility> result, long ns) {
        SpellAbility played = result == null || result.isEmpty() ? null : result.get(0);
        boolean stockOffered = stockList != null && !stockList.isEmpty();
        long[] pr;
        synchronized (LearnedSequencing.class) {
            pr = PRIO.computeIfAbsent(pre.seat, k -> new long[3]);
            pr[1]++;
            if (pre.cands.isEmpty() && !stockOffered && played == null) { pr[0]++; return null; }
        }
        StringBuilder sb = new StringBuilder(2048);
        sb.append("\"seat\":").append(pre.seat).append(",\"turn\":").append(pre.turn).append(",\"phase\":").append(pre.phase);
        sb.append(pre.context);
        sb.append(",\"skipped\":").append(pr[0]).append(",\"prio\":").append(pr[1]);
        sb.append(",\"seqCands\":[");
        for (int i = 0; i < pre.cands.size(); i++) { if (i > 0) sb.append(','); sb.append(pre.cands.get(i).json); }
        sb.append(']');
        if (stockList == null) sb.append(",\"stockList\":null,\"stockIdx\":null");
        else {
            sb.append(",\"stockList\":[");
            for (int i = 0; i < stockList.size(); i++) { if (i > 0) sb.append(','); sb.append((String) stockList.get(i)[3]); }
            sb.append("],\"stockIdx\":[");
            for (int i = 0; i < stockList.size(); i++) { if (i > 0) sb.append(','); sb.append(match(pre.cands, stockList.get(i))); }
            sb.append(']');
        }
        int chosen = played == null ? -1 : match(pre.cands, played);
        sb.append(",\"chosen\":").append(chosen);
        sb.append(",\"played\":");
        if (played == null) sb.append("{\"kind\":\"pass\"}");
        else {
            Card h = played.getHostCard();
            sb.append("{\"kind\":\"").append(played.isLandAbility() ? "land" : played.isSpell() ? "spell" : "ability").append("\",\"info\":");
            StringBuilder ci = new StringBuilder();
            try { ManaView.candJson(ci, ai, played); } catch (RuntimeException e) { ci.setLength(0); ci.append("{\"card\":"); ManaView.str(ci, h == null ? null : h.getName()); ci.append('}'); }
            sb.append(ci).append('}');
        }
        sb.append(",\"pilot\":\"observe\",\"ns\":").append(ns);
        synchronized (LearnedSequencing.class) { pr[0] = 0; pr[2]++; }
        return sb.toString();
    }
}
