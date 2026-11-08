package forge.ai.learned;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * learned-ai D19/D22: deterministic attack-declaration solver, a line-for-line port of the Python attack search
 * ({@code forge-learned-ai/train/combat_search.py} on top of {@code train/combat_solver.py}). Pure: it reads a plain
 * board model ({@link Input}, the same fields as an {@code attackers} combat_snapshot record), never the game, and
 * returns the attack assignment (attacker id to defending player or planeswalker).
 *
 * <p>Objective (D22, lexicographic): feasible = the visible swing-back round (every opponent in turn order attacks us
 * with what can attack on its turn; our remaining blockers assigned greedily, depleted across opponents) leaves us
 * alive with at least {@link Config#lifeFloor} life and under 10 poison; if nothing is feasible the lowest-swing lines
 * are kept. Then most damage onto threat #1 (threat = swing-back capacity with everything home), then #2, ... (within
 * {@link Config#dmgTol}); then lowest round swing, least own value lost, most of theirs killed, fewest attackers,
 * then enumeration order. Defenders block with a deterministic model (good blocks, safe blocks, chumps only facing
 * lethal). {@link Config#lethalScope} adds an optional lethal override (not in the Python; OFF reproduces it).
 *
 * <p>Phase 1: offline only, parity-tested against the Python ({@code CombatSolverParityTest}); not wired online.
 */
public final class CombatSolver {

    public enum SwingBackMode { HARD, PENALTY }
    /** OFF = Python behaviour; LAST = take a line that eliminates every remaining opponent (game win) regardless of
     * swing-back; SAFE = LAST, plus prefer feasible lines that eliminate an opponent; ANY = LAST, plus prefer lines
     * that eliminate an opponent even when infeasible. */
    public enum LethalScope { OFF, LAST, SAFE, ANY }
    /** Visible swing-back estimate used for feasibility, the lowest-swing tie-break and the penalty.
     * SUM_ALL = every opponent in turn order swings everything at us, our blockers depleted across them (Python, D18);
     * MAX_SINGLE = the largest single opponent's full swing against all our home blockers;
     * EXPECTED = sum over opponents of {@link Input#attackProb} x that opponent's full swing against all our home
     * blockers (independent, not depleted). */
    public enum SwingEstimator { SUM_ALL, MAX_SINGLE, EXPECTED }

    /** Parameters. Field defaults = the D22 preset decided by Mitchell (2026-11-08): SUM_ALL swing-back, hard
     * survival with lifeFloor 0 (stay alive against everyone swinging everything), no penalty, lethal on the last
     * opponent always and on another only if survival still holds (SAFE), strict kill. {@link #python()} = the Python
     * SearchParams() defaults the parity fixture was dumped with. */
    public static final class Config {
        public double lifeFloor = 0.0;
        public SwingBackMode swingBackMode = SwingBackMode.HARD;
        /** damage points subtracted from eff[threat #1] per point of round swing (Python swing_penalty; HARD and PENALTY). */
        public double swingWeight = 0.0;
        public LethalScope lethalScope = LethalScope.SAFE;
        public SwingEstimator swingEstimator = SwingEstimator.SUM_ALL;
        /** a line that eliminates a rewarded threat (threat #1; any threat when capNontop is off) strictly beats one
         * that leaves it alive, whatever dmgTol says (fixes "left on 1 life" tying with "killed"). Python (pre-fix)
         * behaves as false. */
        public boolean strictKill = true;
        public int cap = 4096;
        public int chunk = 4;
        public double dmgTol = 1.0;
        public double swingTol = 0.0;
        public double pwWeight = 0.5;
        public boolean capNontop = true;
        public boolean safeBlocks = true;
        public double chumpMargin = 0.0;
        public boolean swingUntappedOnly = false;
        public double cmdValue = 5.0;

        /** D22 preset (same as new Config()) */
        public static Config d22() { return new Config(); }

        /** train/combat_search.py SearchParams() as of the parity fixture (pre strict-kill fix) */
        public static Config python() {
            Config c = new Config();
            c.lifeFloor = 10.0; c.lethalScope = LethalScope.OFF; c.strictKill = false;
            return c;
        }
    }

    /** Phase 2 hook: per-creature attack eligibility (e.g. gets through, survives the block, or trades up; commanders
     * and protected pieces never attack into a killing block). Called for each of the acting seat's creatures that
     * would otherwise be eligible; false keeps it home (it still blocks). Not used by phase 1 (null = all eligible). */
    public interface AttackEligibility {
        boolean mayAttack(Creature c, Input board);
    }

    // ------------------------------------------------------------------------------------------------ input model
    /** One creature as in a combat_snapshot row. kf / pro / col null = absent (schema 1): keywords then come from kw. */
    public static final class Creature {
        public int id, p, t, dmg;
        public boolean tapped, sick, commander, token, canAttack, canBlock;
        public List<String> kw = new ArrayList<>();
        public Integer kf, pro, col;
        /** high-importance piece (phase 2 eligibility rule input; the phase 1 solver ignores it) */
        public boolean protectedPiece;
    }

    public static final class Planeswalker {
        public int id, loyalty;
        public Planeswalker(int id, int loyalty) { this.id = id; this.loyalty = loyalty; }
    }

    public static final class Seat {
        public int seat, life, poison, cmdDmgFromActing;
        public List<Creature> creatures = new ArrayList<>();
        public List<Planeswalker> pws = new ArrayList<>();
    }

    /** seats in turn order from the acting seat (seats.get(0) = acting seat). forcedEligible: creature ids that count
     * as eligible attackers even if canAttack is false (Python: the logged declaration); empty online. */
    public static final class Input {
        public int actingSeat;
        public List<Seat> seats = new ArrayList<>();
        public Set<Integer> forcedEligible = new HashSet<>();
        /** EXPECTED estimator: probability that this opponent seat attacks us on its turn (missing = 1.0) */
        public Map<Integer, Double> attackProb = new HashMap<>();
        /** optional per-creature eligibility filter (phase 2); null = every untapped canAttack creature is eligible */
        public AttackEligibility eligibility;
    }

    /** One attacker's target: a player seat, or a planeswalker (pwId >= 0) controlled by that seat. */
    public static final class Target {
        public final int seat, pwId;
        Target(int seat, int pwId) { this.seat = seat; this.pwId = pwId; }
        public boolean isPlaneswalker() { return pwId >= 0; }
        @Override public String toString() { return pwId >= 0 ? "pw:" + pwId : Integer.toString(seat); }
    }

    public static final class Profile {
        int[][] asg;
        public double[] eff;
        public Map<Integer, Double> dmg = new LinkedHashMap<>(), swing = new LinkedHashMap<>();
        public List<Integer> elim = new ArrayList<>();
        /** per threat rank: the seat is eliminated by this line */
        public boolean[] elimByRank;
        /** swing-back load under the configured estimator (= round for SUM_ALL) */
        public double load;
        public double round, max, swingPoison, lifeAfter, ownLost, oppKilled, attPower;
        public boolean feasible;
        public int nAtt, homeBlockers;
    }

    public static final class Result {
        /** attacker id -> target, in group order (attackers absent stay home) */
        public final Map<Integer, Target> attacks = new LinkedHashMap<>();
        public Profile chosen;
        public String mode;
        public boolean fallback, lethalOverride;
        public int nEval, nFeasible;
        /** opponents' seats by threat rank (#1 first) and their threat values */
        public final List<Integer> threatRank = new ArrayList<>();
        public final List<Double> threatValue = new ArrayList<>();

        public Map<Integer, String> describe() {
            Map<Integer, String> m = new LinkedHashMap<>();
            for (Map.Entry<Integer, Target> e : attacks.entrySet()) m.put(e.getKey(), e.getValue().toString());
            return m;
        }
    }

    // ------------------------------------------------------------------------------------------------ units
    static final String[] KF_NAMES = {"flying", "reach", "first strike", "double strike", "deathtouch", "lifelink",
            "trample", "menace", "vigilance", "defender", "hexproof", "indestructible", "shroud"};
    private static final Set<String> KF_SET = new HashSet<>(Arrays.asList(KF_NAMES));
    private static final Set<String> BASIC_WALK = new HashSet<>(Arrays.asList("islandwalk", "swampwalk", "forestwalk",
            "mountainwalk", "plainswalk"));
    private static final String[] PRO_WORDS = {"white", "blue", "black", "red", "green", "colorless", "colourless",
            "monocolored", "multicolored", "everything", "creatures"};
    private static final int[] PRO_WORD_BIT = {0, 1, 2, 3, 4, 5, 5, 6, 7, 8, 9};
    private static final int PRO_ALL = (1 << 8) | (1 << 9);
    private static final int BLACK = 4;

    static final class Unit {
        int id, seat, p, t, dmg, pro;
        Integer col;
        boolean cmd, tapped, sick, canAttack, canBlock;
        boolean fs, ds, dt, tr, ll, ind, inf, wit, fly, reach, menace, vig, defender, shadow, horse, skulk, fear, intim, unblk;
        double value;
        String sig;   // null for commanders (never grouped)

        int hp() { return t - dmg; }
        int potential() { return Math.max(p, 0) * (ds ? 2 : 1); }
    }

    static String kwBase(String k) {
        int i = k.indexOf(':');
        String b = (i >= 0 ? k.substring(0, i) : k).trim().toLowerCase(Locale.ROOT);
        if (b.startsWith("protection")) return "protection";
        if (b.endsWith("walk") && !b.equals("planeswalk")) return BASIC_WALK.contains(b) ? b : "landwalk";
        return b;
    }

    static Set<String> kwb(Creature c) {
        Set<String> b = new HashSet<>();
        for (String k : c.kw) b.add(kwBase(k));
        if (c.kf != null) {
            b.removeAll(KF_SET);
            for (int i = 0; i < KF_NAMES.length; i++) if ((c.kf >> i & 1) != 0) b.add(KF_NAMES[i]);
        }
        return b;
    }

    static int proFromKw(List<String> kws) {
        int m = 0;
        for (String k : kws) {
            String lk = k.toLowerCase(Locale.ROOT);
            if (!lk.startsWith("protection")) continue;
            int f = lk.indexOf("from");
            String rest = f >= 0 ? lk.substring(f + 4).trim() : "";
            if (rest.startsWith("each color") || rest.startsWith("all colors")) { m |= 31; continue; }
            for (int w = 0; w < PRO_WORDS.length; w++) if (rest.startsWith(PRO_WORDS[w])) { m |= 1 << PRO_WORD_BIT[w]; break; }
        }
        return m;
    }

    static Unit makeUnit(Creature c, int seat, double cmdValue) {
        Unit u = new Unit();
        Set<String> kb = kwb(c);
        u.id = c.id; u.seat = seat; u.p = c.p; u.t = c.t; u.dmg = c.dmg;
        u.cmd = c.commander; u.tapped = c.tapped; u.sick = c.sick; u.canAttack = c.canAttack; u.canBlock = c.canBlock;
        if (c.kf != null) {
            int f = c.kf;
            u.fly = (f & 1) != 0; u.reach = (f >> 1 & 1) != 0; u.fs = (f >> 2 & 1) != 0; u.ds = (f >> 3 & 1) != 0;
            u.dt = (f >> 4 & 1) != 0; u.ll = (f >> 5 & 1) != 0; u.tr = (f >> 6 & 1) != 0; u.menace = (f >> 7 & 1) != 0;
            u.vig = (f >> 8 & 1) != 0; u.defender = (f >> 9 & 1) != 0; u.ind = (f >> 11 & 1) != 0;
        } else {
            u.fly = kb.contains("flying"); u.reach = kb.contains("reach"); u.fs = kb.contains("first strike");
            u.ds = kb.contains("double strike"); u.dt = kb.contains("deathtouch"); u.ll = kb.contains("lifelink");
            u.tr = kb.contains("trample"); u.menace = kb.contains("menace"); u.vig = kb.contains("vigilance");
            u.defender = kb.contains("defender"); u.ind = kb.contains("indestructible");
        }
        u.inf = kb.contains("infect"); u.wit = kb.contains("wither");
        u.shadow = kb.contains("shadow"); u.horse = kb.contains("horsemanship"); u.skulk = kb.contains("skulk");
        u.fear = kb.contains("fear"); u.intim = kb.contains("intimidate"); u.unblk = kb.contains("unblockable");
        u.pro = c.pro != null ? c.pro : proFromKw(c.kw);
        u.col = c.col;
        u.value = Math.max(c.p, 0) + Math.max(c.t, 0) + (c.commander ? cmdValue : 0);
        if (!u.cmd) {
            // Python sig: (p, t, dmg, kwvec[fs ds dt tr ll ind inf wit fly reach menace], vig, shadow, horse, skulk,
            // fear, intim, unblk, pro, col, can_block)
            StringBuilder s = new StringBuilder();
            s.append(u.p).append(',').append(u.t).append(',').append(u.dmg).append(',');
            for (boolean x : new boolean[]{u.fs, u.ds, u.dt, u.tr, u.ll, u.ind, u.inf, u.wit, u.fly, u.reach, u.menace,
                    u.vig, u.shadow, u.horse, u.skulk, u.fear, u.intim, u.unblk}) s.append(x ? '1' : '0');
            s.append(',').append(u.pro).append(',').append(u.col == null ? "N" : u.col.toString()).append(',')
                    .append(u.canBlock ? '1' : '0');
            u.sig = s.toString();
        }
        return u;
    }

    static boolean proHits(int pro, Integer col) {
        if (pro == 0 || col == null) return false;
        int n = Integer.bitCount(col);
        return (col & pro & 31) != 0 || (n == 0 && (pro >> 5 & 1) != 0) || (n == 1 && (pro >> 6 & 1) != 0)
                || (n >= 2 && (pro >> 7 & 1) != 0);
    }

    /** damage from creature src to x is prevented by x's protection */
    static boolean protectedFrom(Unit x, Unit src) { return (x.pro & PRO_ALL) != 0 || proHits(x.pro, src.col); }

    static boolean canBlock(Unit b, Unit a) {
        if (a.unblk || (a.fly && !(b.fly || b.reach)) || a.shadow != b.shadow || (a.horse && !b.horse)) return false;
        if (a.skulk && b.p > a.p) return false;
        if (a.fear && (b.col == null || (b.col & BLACK) == 0)) return false;
        if (a.intim && (b.col == null || a.col == null || (b.col & a.col) == 0)) return false;
        if (a.pro != 0 && ((a.pro & PRO_ALL) != 0 || proHits(a.pro, b.col))) return false;
        return true;
    }

    /** exact 1-v-1 combat (a attacks, b blocks): {a dies, b dies} */
    static boolean[] duel(Unit a, Unit b) {
        boolean bProt = protectedFrom(b, a), aProt = protectedFrom(a, b);
        int am = 0, bm = 0; boolean adt = false, bdt = false, aa = true, ba = true;
        for (int step = 0; step < 2; step++) {
            boolean ad = aa && (step == 0 ? (a.fs || a.ds) : (!a.fs || a.ds));
            boolean bd = ba && (step == 0 ? (b.fs || b.ds) : (!b.fs || b.ds));
            if (ad && a.p > 0 && !bProt) { bm += a.p; bdt |= a.dt; }
            if (bd && b.p > 0 && !aProt) { am += b.p; adt |= b.dt; }
            aa = aa && (a.ind || !(am >= a.hp() || adt));
            ba = ba && (b.ind || !(bm >= b.hp() || bdt));
        }
        return new boolean[]{!aa, !ba};
    }

    private static final Comparator<Unit> BY_VALUE_ID = (x, y) -> {
        int c = Double.compare(x.value, y.value);
        return c != 0 ? c : Integer.compare(x.id, y.id);
    };

    // ------------------------------------------------------------------------------------------------ block models
    static final class Att { final Unit u; final int slot; Att(Unit u, int slot) { this.u = u; this.slot = slot; } }

    /** deterministic defender: returns attacker id -> blockers (insertion order) */
    static Map<Integer, List<Unit>> defenderBlocks(List<Att> atts, List<Unit> blockers, int life, int poison,
                                                   int cmdPrior, int playerSlot, Config P) {
        List<Unit> avail = new ArrayList<>(blockers);
        avail.sort(BY_VALUE_ID);
        Map<Integer, List<Unit>> blocks = new LinkedHashMap<>();
        List<Att> order = new ArrayList<>(atts);
        order.sort((x, y) -> {
            int c = Integer.compare(y.u.potential(), x.u.potential());
            if (c != 0) return c;
            c = Double.compare(y.u.value, x.u.value);
            return c != 0 ? c : Integer.compare(x.u.id, y.u.id);
        });
        for (Att at : order) {
            Unit a = at.u;
            if (a.menace || avail.isEmpty()) continue;
            List<Unit> legal = new ArrayList<>();
            for (Unit b : avail) if (canBlock(b, a)) legal.add(b);
            if (legal.isEmpty()) continue;
            Unit pick = null;
            for (Unit b : legal) { boolean[] d = duel(a, b); if (d[0] && !d[1]) { pick = b; break; } }
            if (pick == null && P.safeBlocks && !a.tr)
                for (Unit b : legal) if (!duel(a, b)[1]) { pick = b; break; }
            if (pick != null) { blocks.put(a.id, new ArrayList<>(List.of(pick))); avail.remove(pick); }
        }
        int[] cur = incoming(atts, blocks, playerSlot);
        while (!avail.isEmpty() && lethalIncoming(cur, life, poison, cmdPrior, P)) {
            double bestSaved = 0; Unit bestA = null; List<Unit> bestBl = null;
            for (Att at : order) {
                Unit a = at.u;
                if (at.slot != playerSlot || blocks.containsKey(a.id)) continue;
                List<Unit> legal = new ArrayList<>();
                for (Unit b : avail) if (canBlock(b, a)) legal.add(b);
                int need = a.menace ? 2 : 1;
                if (legal.size() < need) continue;
                List<Unit> bl = legal.subList(0, need);
                int saved = a.potential();
                if (a.tr) { int s = 0; for (Unit b : bl) s += Math.max(b.hp(), 0); saved = Math.min(a.potential(), s); }
                if (saved > 0 && (bestA == null || saved > bestSaved)) { bestSaved = saved; bestA = a; bestBl = new ArrayList<>(bl); }
            }
            if (bestA == null) break;
            blocks.put(bestA.id, bestBl);
            for (Unit b : bestBl) avail.remove(b);
            cur = incoming(atts, blocks, playerSlot);
        }
        return blocks;
    }

    private static int[] incoming(List<Att> atts, Map<Integer, List<Unit>> blocks, int playerSlot) {
        int d = 0, ps = 0, cm = 0;
        for (Att at : atts) {
            if (at.slot != playerSlot) continue;
            Unit a = at.u; int pw = a.potential(); List<Unit> bl = blocks.get(a.id);
            if (bl != null && !bl.isEmpty()) {
                if (!a.tr) continue;
                int s = 0; for (Unit b : bl) s += Math.max(b.hp(), 0);
                pw = Math.max(0, pw - s);
            }
            if (a.inf) ps += pw; else d += pw;
            if (a.cmd) cm += pw;
        }
        return new int[]{d, ps, cm};
    }

    private static boolean lethalIncoming(int[] x, int life, int poison, int cmdPrior, Config P) {
        return x[0] >= life - P.chumpMargin || poison + x[1] >= 10 || (x[2] > 0 && cmdPrior + x[2] >= 21);
    }

    static final class Swing { final int dmg, ps; final Set<Integer> dead; Swing(int d, int p, Set<Integer> x) { dmg = d; ps = p; dead = x; } }

    /** one opponent attacks us with atts; our blockers assigned greedily to minimise damage */
    static Swing swingBack(List<Unit> atts, List<Unit> blockers) {
        List<Unit> avail = new ArrayList<>(blockers);
        avail.sort(BY_VALUE_ID);
        int dmg = 0, ps = 0; Set<Integer> dead = new HashSet<>();
        List<Unit> sorted = new ArrayList<>(atts);
        sorted.sort((x, y) -> { int c = Integer.compare(y.potential(), x.potential()); return c != 0 ? c : Integer.compare(x.id, y.id); });
        for (Unit a : sorted) {
            int pw = a.potential();
            if (pw <= 0) continue;
            List<Unit> legal = new ArrayList<>();
            for (Unit b : avail) if (canBlock(b, a)) legal.add(b);
            int need = a.menace ? 2 : 1;
            int through = pw;
            if (legal.size() >= need) {
                legal.sort((x, y) -> {
                    int c = Boolean.compare(duel(a, x)[1], duel(a, y)[1]);
                    if (c != 0) return c;
                    c = Boolean.compare((x.fly || x.reach) && !a.fly, (y.fly || y.reach) && !a.fly);
                    return c != 0 ? c : BY_VALUE_ID.compare(x, y);
                });
                List<Unit> bl = legal.subList(0, need);
                if (a.tr) { int s = 0; for (Unit b : bl) s += Math.max(b.hp(), 0); through = Math.max(0, pw - s); }
                else through = 0;
                for (Unit b : new ArrayList<>(bl)) {
                    avail.remove(b);
                    if (duel(a, b)[1]) dead.add(b.id);
                    if (a.tr) break;
                }
            }
            if (a.inf) ps += through; else dmg += through;
        }
        return new Swing(dmg, ps, dead);
    }

    // ------------------------------------------------------------------------------------------------ resolution
    /** outcome of one defender sub-problem (combat_solver.resolve for one row) */
    static final class Sub {
        int seat;
        List<Att> atts = new ArrayList<>();
        List<Integer> gidx = new ArrayList<>();
        Map<Integer, List<Unit>> blocks;
        double dmg, ps, eff, pw, gain, ownLost, oppKilled;
        boolean elim;
        int[] deadByGroup;
        Set<Integer> deadBlk = new HashSet<>();
        String deadBlkKey;
    }

    private static final class Ctx {
        final Config P;
        final Input in;
        final Seat me;
        final List<Seat> opps = new ArrayList<>();
        final Map<Integer, Unit> units = new HashMap<>();
        final int S, mySlot;
        final List<Target> targets = new ArrayList<>();
        final List<Integer> targetSlot = new ArrayList<>();
        final List<Planeswalker> pws = new ArrayList<>();
        final List<Integer> pwSeat = new ArrayList<>();
        int T;
        final List<Unit> eligible = new ArrayList<>(), baseBlockers = new ArrayList<>();
        final List<List<Unit>> groups = new ArrayList<>();
        final List<List<int[]>> options = new ArrayList<>();
        boolean[] groupBlocks;
        final Map<Integer, Seat> seatOf = new HashMap<>();
        final Map<Integer, List<Unit>> oppBlk = new HashMap<>(), oppAtt = new HashMap<>();
        final Map<String, Sub> subMemo = new HashMap<>();
        final Map<String, Swing> swingMemo = new HashMap<>();
        final List<Integer> rank = new ArrayList<>();
        final List<Double> threatVal = new ArrayList<>();
        final double[] life, poison, cmdPrior;

        Ctx(Input in, Config P) {
            this.P = P; this.in = in;
            me = in.seats.get(0);
            if (me.seat != in.actingSeat) throw new IllegalArgumentException("seats[0] must be the acting seat");
            for (int i = 1; i < in.seats.size(); i++) { Seat s = in.seats.get(i); if (s.life > 0 && s.poison < 10) opps.add(s); }
            int smax = 1;
            for (Seat s : in.seats) {
                smax = Math.max(smax, s.seat); seatOf.put(s.seat, s);
                for (Creature c : s.creatures) units.put(c.id, makeUnit(c, s.seat, P.cmdValue));
            }
            S = smax; mySlot = me.seat - 1;
            life = new double[S]; poison = new double[S]; cmdPrior = new double[S];
            for (Seat s : in.seats) { life[s.seat - 1] = s.life; poison[s.seat - 1] = s.poison; cmdPrior[s.seat - 1] = s.cmdDmgFromActing; }
            for (Seat s : opps) { targets.add(new Target(s.seat, -1)); targetSlot.add(s.seat - 1); }
            for (Seat s : opps) for (Planeswalker w : s.pws) { pws.add(w); pwSeat.add(s.seat); }
            for (int j = 0; j < pws.size(); j++) { targets.add(new Target(pwSeat.get(j), pws.get(j).id)); targetSlot.add(S + j); }
            T = targets.size();
            List<Unit> mine = new ArrayList<>();
            Set<Integer> vetoed = new HashSet<>();
            for (Creature c : me.creatures) {
                mine.add(units.get(c.id));
                if (in.eligibility != null && !in.forcedEligible.contains(c.id) && !in.eligibility.mayAttack(c, in)) vetoed.add(c.id);
            }
            for (Unit u : mine) if ((u.canAttack || in.forcedEligible.contains(u.id)) && !u.tapped && !vetoed.contains(u.id)) eligible.add(u);
            for (Unit u : mine) if (u.tapped && in.forcedEligible.contains(u.id)) eligible.add(u);
            Set<Integer> elig = new HashSet<>();
            for (Unit u : eligible) elig.add(u.id);
            for (Unit u : mine) if (!elig.contains(u.id) && !u.tapped && u.canBlock) baseBlockers.add(u);
            Map<String, List<Unit>> g = new LinkedHashMap<>();
            for (Unit u : eligible) g.computeIfAbsent(u.sig != null ? "s" + u.sig : "c" + u.id, k -> new ArrayList<>()).add(u);
            groups.addAll(g.values());
            groups.sort((x, y) -> {
                Unit a = x.get(0), b = y.get(0);
                int c = Integer.compare(b.potential(), a.potential());
                if (c != 0) return c;
                c = Double.compare(b.value, a.value);
                return c != 0 ? c : Integer.compare(a.id, b.id);
            });
            groupBlocks = new boolean[groups.size()];
            for (int i = 0; i < groups.size(); i++) {
                options.add(groupOptions(groups.get(i).size()));
                groupBlocks[i] = groups.get(i).get(0).canBlock && !groups.get(i).get(0).tapped;
            }
            for (Seat s : opps) {
                List<Unit> blk = new ArrayList<>(), att = new ArrayList<>();
                for (Creature c : s.creatures) {
                    Unit u = units.get(c.id);
                    if (!c.tapped && c.canBlock) blk.add(u);
                    if (u.defender || u.p <= 0) continue;
                    if (P.swingUntappedOnly && u.tapped) continue;
                    if (!(u.canAttack || u.tapped || u.sick)) continue;
                    att.add(u);
                }
                oppBlk.put(s.seat, blk); oppAtt.put(s.seat, att);
            }
            threats();
        }

        private List<int[]> groupOptions(int m) {
            int ch = P.chunk;
            int[] sizes;
            if (m <= ch) { sizes = new int[m]; Arrays.fill(sizes, 1); }
            else { sizes = new int[ch]; for (int i = 0; i < ch; i++) sizes[i] = m / ch + (i < m % ch ? 1 : 0); }
            Set<String> seen = new HashSet<>(); List<int[]> opts = new ArrayList<>();
            if (sizes.length <= 6) {
                int[] combo = new int[sizes.length];
                while (true) {
                    int[] v = new int[T];
                    for (int i = 0; i < sizes.length; i++) if (combo[i] < T) v[combo[i]] += sizes[i];
                    if (seen.add(Arrays.toString(v))) opts.add(v);
                    int k = sizes.length - 1;
                    while (k >= 0 && ++combo[k] > T) { combo[k] = 0; k--; }
                    if (k < 0) break;
                }
            }
            opts.sort((x, y) -> {
                int c = Integer.compare(sum(x), sum(y));
                return c != 0 ? c : Arrays.compare(x, y);
            });
            return opts;
        }

        private void threats() {
            List<Unit> allHome = new ArrayList<>(baseBlockers);
            for (Unit u : eligible) if (u.canBlock && !u.tapped) allHome.add(u);
            List<double[]> out = new ArrayList<>();   // threat, raw, turnPos, seat
            for (int i = 0; i < opps.size(); i++) {
                Seat s = opps.get(i);
                Swing r = swingBack(oppAtt.get(s.seat), allHome);
                int raw = 0; for (Unit u : oppAtt.get(s.seat)) raw += u.potential();
                double thr = r.dmg + r.ps * (double) me.life / Math.max(1, 10 - me.poison);
                out.add(new double[]{thr, raw, i + 1, s.seat});
            }
            out.sort((x, y) -> {
                int c = Double.compare(y[0], x[0]);
                if (c != 0) return c;
                c = Double.compare(y[1], x[1]);
                return c != 0 ? c : Double.compare(x[2], y[2]);
            });
            for (double[] t : out) { rank.add((int) t[3]); threatVal.add(t[0]); }
        }

        int[][] allHome() { return new int[groups.size()][T]; }
    }

    static int sum(int[] v) { int s = 0; for (int x : v) s += x; return s; }

    private static String subKey(int seat, List<int[]> key) {
        StringBuilder b = new StringBuilder().append(seat).append('|');
        for (int[] k : key) b.append(k[0]).append(',').append(k[1]).append(',').append(k[2]).append(';');
        return b.toString();
    }

    private static Sub makeSub(Ctx ctx, List<int[]> key, int seat) {
        Seat st = ctx.seatOf.get(seat);
        Sub sub = new Sub(); sub.seat = seat;
        Map<Integer, Integer> taken = new HashMap<>();
        for (int[] k : key) {
            int gi = k[0], t = k[1], n = k[2];
            int from = taken.getOrDefault(gi, 0);
            List<Unit> g = ctx.groups.get(gi);
            for (int i = from; i < Math.min(g.size(), from + n); i++) { sub.atts.add(new Att(g.get(i), ctx.targetSlot.get(t))); sub.gidx.add(gi); }
            taken.put(gi, from + n);
        }
        sub.blocks = defenderBlocks(sub.atts, ctx.oppBlk.get(seat), st.life, st.poison, st.cmdDmgFromActing, seat - 1, ctx.P);
        resolve(ctx, sub, st);
        return sub;
    }

    /** combat_solver.resolve for one sub-problem: two damage steps, lethal-in-order assignment, trample, deathtouch,
     * lifelink, indestructible (protected blockers counted indestructible, as the Python batch does), infect. */
    private static void resolve(Ctx ctx, Sub sub, Seat st) {
        int A = sub.atts.size();
        List<Unit> blkU = new ArrayList<>();
        int[][] assign = new int[A][];
        boolean[] blkInd = new boolean[0];
        List<Boolean> indL = new ArrayList<>();
        for (int j = 0; j < A; j++) {
            Att at = sub.atts.get(j);
            List<Unit> bl = sub.blocks.getOrDefault(at.u.id, Collections.emptyList());
            assign[j] = new int[bl.size()];
            for (int k = 0; k < bl.size(); k++) {
                Unit b = bl.get(k);
                assign[j][k] = blkU.size(); blkU.add(b);
                indL.add(b.ind || protectedFrom(b, at.u));
            }
        }
        int B = blkU.size(), S = ctx.S, W = ctx.pws.size();
        blkInd = new boolean[B];
        for (int i = 0; i < B; i++) blkInd[i] = indL.get(i);
        boolean[] attAlive = new boolean[A], blkAlive = new boolean[B], attDt = new boolean[A], blkDt = new boolean[B];
        double[] attMark = new double[A], blkMark = new double[B];
        for (int j = 0; j < A; j++) { attAlive[j] = true; attMark[j] = sub.atts.get(j).u.dmg; }
        for (int i = 0; i < B; i++) { blkAlive[i] = true; blkMark[i] = blkU.get(i).dmg; }
        double[] tgtDmg = new double[S + W], tgtPoison = new double[S + W], cmdAdd = new double[S + W], lifeGain = new double[S];
        for (int step = 0; step < 2; step++) {
            double[][] toBlk = new double[A][], toAtt = new double[A][];
            double[] toTgt = new double[A];
            for (int j = 0; j < A; j++) {
                Unit a = sub.atts.get(j).u; int[] as = assign[j]; int K = as.length;
                boolean aDeals = step == 0 ? (a.fs || a.ds) : (!a.fs || a.ds);
                boolean aAct = attAlive[j] && aDeals;
                double power = aAct ? Math.max(a.p, 0) : 0;
                boolean blocked = K > 0;
                toBlk[j] = new double[K]; toAtt[j] = new double[K];
                double before = 0, used = 0; int last = -1; boolean anyAlive = false;
                for (int k = 0; k < K; k++) {
                    int bi = as[k]; Unit b = blkU.get(bi);
                    boolean gAlive = blkAlive[bi];
                    double lethal = Math.max(b.t - blkMark[bi], 0);
                    double need = gAlive ? ((a.dt && lethal > 0) ? 1 : lethal) : 0;
                    toBlk[j][k] = Math.min(Math.max(power - before, 0), need);
                    before += need; used += toBlk[j][k];
                    if (gAlive) { last = k; anyAlive = true; }
                }
                double left = power - used;
                if (!a.tr && anyAlive && blocked) toBlk[j][Math.max(last, 0)] += left;
                toTgt[j] = blocked ? (a.tr ? left : 0) : power;
                boolean[] bDealsArr = new boolean[K];
                for (int k = 0; k < K; k++) {
                    int bi = as[k]; Unit b = blkU.get(bi);
                    boolean bDeals = step == 0 ? (b.fs || b.ds) : (!b.fs || b.ds);
                    bDealsArr[k] = blkAlive[bi] && bDeals;
                    toAtt[j][k] = bDealsArr[k] ? Math.max(b.p, 0) : 0;
                }
            }
            // apply simultaneously
            for (int j = 0; j < A; j++) {
                Att at = sub.atts.get(j); Unit a = at.u; int[] as = assign[j];
                double sumBlk = 0, attIn = 0;
                for (int k = 0; k < as.length; k++) {
                    int bi = as[k]; Unit b = blkU.get(bi);
                    blkMark[bi] += toBlk[j][k]; sumBlk += toBlk[j][k];
                    if (a.dt && toBlk[j][k] > 0) blkDt[bi] = true;
                    attIn += toAtt[j][k];
                    if (b.dt && toAtt[j][k] > 0) attDt[j] = true;
                    if (b.ll) lifeGain[b.seat - 1] += toAtt[j][k];
                }
                attMark[j] += attIn;
                if (a.inf) tgtPoison[at.slot] += toTgt[j]; else tgtDmg[at.slot] += toTgt[j];
                if (a.cmd) cmdAdd[at.slot] += toTgt[j];
                if (a.ll) lifeGain[ctx.mySlot] += toTgt[j] + sumBlk;
            }
            for (int j = 0; j < A; j++) {
                Unit a = sub.atts.get(j).u;
                if (attAlive[j] && !a.ind && (attMark[j] >= a.t || attDt[j])) attAlive[j] = false;
            }
            for (int i = 0; i < B; i++) {
                Unit b = blkU.get(i);
                if (blkAlive[i] && !blkInd[i] && (blkMark[i] >= b.t || blkDt[i])) blkAlive[i] = false;
            }
        }
        int sl = sub.seat - 1;
        sub.dmg = tgtDmg[sl]; sub.ps = tgtPoison[sl];
        double lifeAfter = ctx.life[sl] - tgtDmg[sl] + lifeGain[sl];
        sub.elim = lifeAfter <= 0 || ctx.poison[sl] + tgtPoison[sl] >= 10 || ctx.cmdPrior[sl] + cmdAdd[sl] >= 21;
        sub.eff = sub.elim ? st.life : Math.min(st.life, sub.dmg + sub.ps * st.life / Math.max(1, 10 - st.poison));
        double pw = 0;
        for (int j = 0; j < W; j++)
            if (ctx.pwSeat.get(j) == sub.seat) pw += Math.min(tgtDmg[S + j] + tgtPoison[S + j], ctx.pws.get(j).loyalty);
        sub.pw = pw;
        sub.gain = lifeGain[ctx.mySlot];
        sub.deadByGroup = new int[ctx.groups.size()];
        for (int j = 0; j < A; j++)
            if (!attAlive[j]) { sub.deadByGroup[sub.gidx.get(j)]++; sub.ownLost += sub.atts.get(j).u.value; }
        List<Integer> dead = new ArrayList<>();
        for (int i = 0; i < B; i++) if (!blkAlive[i]) { dead.add(blkU.get(i).id); sub.deadBlk.add(blkU.get(i).id); }
        Collections.sort(dead);
        sub.deadBlkKey = dead.toString();
        for (int id : dead) sub.oppKilled += ctx.units.get(id).value;
    }

    // ------------------------------------------------------------------------------------------------ evaluation
    private static List<Profile> evaluate(Ctx ctx, List<int[][]> asgs) {
        Input in = ctx.in;
        Config P = ctx.P;
        List<Profile> out = new ArrayList<>(asgs.size());
        for (int[][] asg : asgs) {
            Map<Integer, Sub> subs = new LinkedHashMap<>();
            for (Seat s : ctx.opps) {
                List<int[]> key = new ArrayList<>();
                for (int gi = 0; gi < asg.length; gi++)
                    for (int t = 0; t < ctx.T; t++)
                        if (asg[gi][t] != 0 && ctx.targets.get(t).seat == s.seat) key.add(new int[]{gi, t, asg[gi][t]});
                String mk = subKey(s.seat, key);
                Sub sub = ctx.subMemo.get(mk);
                if (sub == null) { sub = makeSub(ctx, key, s.seat); ctx.subMemo.put(mk, sub); }
                subs.put(s.seat, sub);
            }
            double gain = 0; for (Sub s : subs.values()) gain += s.gain;
            double lifeAfter = ctx.me.life + gain;
            List<Unit> home = new ArrayList<>(ctx.baseBlockers);
            for (int gi = 0; gi < ctx.groups.size(); gi++) {
                if (!ctx.groupBlocks[gi]) continue;
                List<Unit> g = ctx.groups.get(gi);
                int sent = sum(asg[gi]);
                if (sent < g.size()) home.addAll(g.subList(sent, g.size()));
                if (sent > 0 && g.get(0).vig) {
                    int dead = 0; for (Sub s : subs.values()) dead += s.deadByGroup[gi];
                    home.addAll(g.subList(0, Math.max(0, sent - dead)));
                }
            }
            Profile pr = new Profile(); pr.asg = asg;
            List<Unit> avail = home; double swPs = 0, rnd = 0, mx = 0; boolean any = false;
            for (Seat s : ctx.opps) {
                Sub sub = subs.get(s.seat);
                StringBuilder kb = new StringBuilder().append(s.seat).append('|');
                for (Unit u : avail) kb.append(u.id).append(',');
                kb.append('|').append(sub.deadBlkKey);
                String mk = kb.toString();
                Swing r = ctx.swingMemo.get(mk);
                if (r == null) {
                    List<Unit> att = ctx.oppAtt.get(s.seat);
                    if (!sub.deadBlk.isEmpty()) { List<Unit> a2 = new ArrayList<>(); for (Unit u : att) if (!sub.deadBlk.contains(u.id)) a2.add(u); att = a2; }
                    r = swingBack(att, avail);
                    ctx.swingMemo.put(mk, r);
                }
                pr.swing.put(s.seat, (double) r.dmg); swPs += r.ps; rnd += r.dmg;
                mx = any ? Math.max(mx, r.dmg) : r.dmg; any = true;
                if (!r.dead.isEmpty()) { List<Unit> a2 = new ArrayList<>(); for (Unit u : avail) if (!r.dead.contains(u.id)) a2.add(u); avail = a2; }
            }
            double load = rnd;
            if (P.swingEstimator != SwingEstimator.SUM_ALL) {
                double best = 0, exp = 0, ps = 0, psMax = 0;
                for (Seat s : ctx.opps) {
                    Sub sub = subs.get(s.seat);
                    List<Unit> att = ctx.oppAtt.get(s.seat);
                    if (!sub.deadBlk.isEmpty()) { List<Unit> a2 = new ArrayList<>(); for (Unit u : att) if (!sub.deadBlk.contains(u.id)) a2.add(u); att = a2; }
                    Swing r = swingBack(att, home);
                    double pa = in.attackProb.getOrDefault(s.seat, 1.0);
                    if (r.dmg > best) best = r.dmg;
                    psMax = Math.max(psMax, r.ps);
                    exp += pa * r.dmg; ps += pa * r.ps;
                }
                load = P.swingEstimator == SwingEstimator.MAX_SINGLE ? best : exp;
                swPs = P.swingEstimator == SwingEstimator.MAX_SINGLE ? psMax : ps;
            }
            pr.load = load;
            boolean survives = load < lifeAfter && ctx.me.poison + swPs < 10;
            pr.feasible = P.swingBackMode == SwingBackMode.HARD ? survives && lifeAfter - load >= P.lifeFloor : survives;
            pr.eff = new double[ctx.rank.size()];
            pr.elimByRank = new boolean[ctx.rank.size()];
            for (int ri = 0; ri < ctx.rank.size(); ri++) {
                int seat = ctx.rank.get(ri); Sub s = subs.get(seat);
                double e = s.eff + P.pwWeight * s.pw;
                if (ri > 0 && P.capNontop && ctx.rank.size() > 1) e = Math.min(e, ctx.seatOf.get(seat).life - 1 + P.pwWeight * s.pw);
                if (ri == 0) e -= P.swingWeight * load;
                pr.eff[ri] = e;
                pr.elimByRank[ri] = s.elim;
            }
            for (int gi = 0; gi < asg.length; gi++)
                for (int t = 0; t < ctx.T; t++) {
                    int n = asg[gi][t];
                    if (n != 0) { pr.nAtt += n; pr.attPower += n * ctx.groups.get(gi).get(0).potential(); }
                }
            for (Map.Entry<Integer, Sub> e : subs.entrySet()) {
                pr.dmg.put(e.getKey(), e.getValue().dmg);
                if (e.getValue().elim) pr.elim.add(e.getKey());
                pr.ownLost += e.getValue().ownLost; pr.oppKilled += e.getValue().oppKilled;
            }
            pr.round = rnd; pr.max = mx; pr.swingPoison = swPs; pr.lifeAfter = lifeAfter; pr.homeBlockers = home.size();
            out.add(pr);
        }
        return out;
    }

    private static int compareBeamKey(Profile a, Profile b, Config P) {
        int c = Boolean.compare(a.feasible, b.feasible);
        if (c != 0) return c;
        for (int i = 0; i < a.eff.length; i++) {
            if (P.strictKill && rewarded(i, a.eff.length, P)) {
                c = Boolean.compare(a.elimByRank[i], b.elimByRank[i]);
                if (c != 0) return c;
            }
            c = Double.compare(q(a.eff[i], P), q(b.eff[i], P));
            if (c != 0) return c;
        }
        c = Double.compare(-a.load, -b.load); if (c != 0) return c;
        c = Double.compare(-a.ownLost, -b.ownLost); if (c != 0) return c;
        c = Double.compare(a.oppKilled, b.oppKilled); if (c != 0) return c;
        return Integer.compare(-a.nAtt, -b.nAtt);
    }

    /** elimination of the threat at rank k is rewarded by the objective (non-top threats are capped at life-1) */
    private static boolean rewarded(int k, int n, Config P) { return k == 0 || !P.capNontop || n <= 1; }

    private static double q(double x, Config P) { return P.dmgTol > 0 ? Math.floor(x / P.dmgTol + 1e-9) : x; }

    /** Python select(): returns {index, fallback 0/1} */
    static int[] select(List<Profile> profiles, Config P) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).feasible) idx.add(i);
        boolean fallback = idx.isEmpty();
        if (fallback) {
            double m = Double.POSITIVE_INFINITY;
            for (Profile p : profiles) m = Math.min(m, p.load);
            for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).load <= m + P.swingTol) idx.add(i);
        }
        return new int[]{selectAmong(profiles, idx, P), fallback ? 1 : 0};
    }

    private static int selectAmong(List<Profile> profiles, List<Integer> idx, Config P) {
        int nd = profiles.isEmpty() ? 0 : profiles.get(0).eff.length;
        for (int k = 0; k < nd; k++) {
            double best = Double.NEGATIVE_INFINITY;
            if (P.strictKill && rewarded(k, nd, P)) {
                List<Integer> kill = new ArrayList<>();
                for (int i : idx) if (profiles.get(i).elimByRank[k]) kill.add(i);
                if (!kill.isEmpty()) idx = kill;
            }
            for (int i : idx) best = Math.max(best, profiles.get(i).eff[k]);
            idx = keep(idx, profiles, k, best, P);
        }
        double m = Double.POSITIVE_INFINITY;
        for (int i : idx) m = Math.min(m, profiles.get(i).load);
        List<Integer> n1 = new ArrayList<>();
        for (int i : idx) if (profiles.get(i).load <= m + P.swingTol + 1e-9) n1.add(i);
        return tailSelect(profiles, n1);
    }

    private static List<Integer> keep(List<Integer> idx, List<Profile> profiles, int k, double best, Config P) {
        List<Integer> out = new ArrayList<>();
        for (int i : idx) if (profiles.get(i).eff[k] >= best - P.dmgTol - 1e-9) out.add(i);
        return out;
    }

    /** own_lost min, opp_killed max, n_att min, first */
    private static int tailSelect(List<Profile> profiles, List<Integer> idx) {
        double m = Double.POSITIVE_INFINITY;
        for (int i : idx) m = Math.min(m, profiles.get(i).ownLost);
        List<Integer> a = new ArrayList<>();
        for (int i : idx) if (profiles.get(i).ownLost <= m + 1e-9) a.add(i);
        m = Double.NEGATIVE_INFINITY;
        for (int i : a) m = Math.max(m, profiles.get(i).oppKilled);
        List<Integer> b = new ArrayList<>();
        for (int i : a) if (profiles.get(i).oppKilled >= m - 1e-9) b.add(i);
        int mn = Integer.MAX_VALUE;
        for (int i : b) mn = Math.min(mn, profiles.get(i).nAtt);
        for (int i : b) if (profiles.get(i).nAtt == mn) return i;
        return b.get(0);
    }

    private static int[][] homeRest(int[][] partial, int G, int T) {
        int[][] out = new int[G][];
        for (int i = 0; i < G; i++) out[i] = i < partial.length ? partial[i] : new int[T];
        return out;
    }

    private static boolean sameAsg(int[][] a, int[][] b) { return Arrays.deepEquals(a, b); }

    // ------------------------------------------------------------------------------------------------ entry point
    /** Attack search for one board; null when no opponent is alive. */
    public static Result solve(Input in, Config P) {
        if (P == null) P = new Config();
        Ctx ctx = new Ctx(in, P);
        if (ctx.opps.isEmpty()) return null;
        int G = ctx.groups.size();
        long nFull = 1;
        for (List<int[]> o : ctx.options) { nFull *= o.size(); if (nFull > Integer.MAX_VALUE) break; }
        List<int[][]> cands; List<Profile> profs; String mode;
        if (nFull <= P.cap) {
            cands = new ArrayList<>();
            int[] ix = new int[G];
            while (true) {
                int[][] a = new int[G][];
                for (int g = 0; g < G; g++) a[g] = ctx.options.get(g).get(ix[g]);
                cands.add(a);
                int k = G - 1;
                while (k >= 0 && ++ix[k] >= ctx.options.get(k).size()) { ix[k] = 0; k--; }
                if (k < 0) break;
            }
            profs = evaluate(ctx, cands); mode = "full";
        } else {
            mode = "beam";
            int so = 0; for (List<int[]> o : ctx.options) so += o.size();
            int width = Math.max(4, P.cap / Math.max(1, so));
            List<int[][]> states = new ArrayList<>(); states.add(new int[0][]);
            cands = null; profs = null;
            final Config PP = P;
            for (int gi = 0; gi < G; gi++) {
                List<int[][]> ext = new ArrayList<>();
                for (int[][] st : states)
                    for (int[] o : ctx.options.get(gi)) { int[][] e = Arrays.copyOf(st, st.length + 1); e[st.length] = o; ext.add(e); }
                List<int[][]> full = new ArrayList<>();
                for (int[][] e : ext) full.add(homeRest(e, G, ctx.T));
                List<Profile> pr = evaluate(ctx, full);
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < ext.size(); i++) order.add(i);
                order.sort((x, y) -> compareBeamKey(pr.get(y), pr.get(x), PP));   // descending, stable
                if (gi == G - 1) {
                    cands = new ArrayList<>(); profs = new ArrayList<>();
                    for (int i : order) { cands.add(full.get(i)); profs.add(pr.get(i)); }
                } else {
                    states = new ArrayList<>();
                    for (int i = 0; i < Math.min(width, order.size()); i++) states.add(ext.get(order.get(i)));
                }
            }
            int[][] home = ctx.allHome();
            boolean has = false;
            for (int[][] c : cands) if (sameAsg(c, home)) { has = true; break; }
            if (!has) { cands.add(home); profs.addAll(evaluate(ctx, List.<int[][]>of(home))); }
        }
        int[] sel = select(profs, P);
        int ci = sel[0];
        Result res = new Result();
        int lo = lethalOverride(ctx, profs, P);
        if (lo >= 0) { ci = lo; res.lethalOverride = true; }
        Profile chosen = profs.get(ci);
        res.chosen = chosen; res.mode = mode; res.fallback = sel[1] == 1; res.nEval = profs.size();
        for (Profile p : profs) if (p.feasible) res.nFeasible++;
        res.threatRank.addAll(ctx.rank); res.threatValue.addAll(ctx.threatVal);
        for (int gi = 0; gi < G; gi++) {
            List<Unit> g = ctx.groups.get(gi); int k = 0;
            for (int t = 0; t < ctx.T; t++)
                for (int n = 0; n < chosen.asg[gi][t] && k < g.size(); n++, k++) res.attacks.put(g.get(k).id, ctx.targets.get(t));
        }
        return res;
    }

    /** index of the lethal-override line, or -1 (OFF, or no applicable line) */
    private static int lethalOverride(Ctx ctx, List<Profile> profs, Config P) {
        if (P.lethalScope == LethalScope.OFF) return -1;
        List<Integer> win = new ArrayList<>();
        for (int i = 0; i < profs.size(); i++) if (profs.get(i).elim.size() == ctx.opps.size()) win.add(i);
        if (!win.isEmpty()) return tailSelect(profs, win);
        if (P.lethalScope == LethalScope.LAST) return -1;
        int most = 0;
        for (Profile p : profs) if (P.lethalScope == LethalScope.ANY || p.feasible) most = Math.max(most, p.elim.size());
        if (most == 0) return -1;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < profs.size(); i++) {
            Profile p = profs.get(i);
            if ((P.lethalScope == LethalScope.ANY || p.feasible) && p.elim.size() == most) idx.add(i);
        }
        return selectAmong(profs, idx, P);
    }
}
