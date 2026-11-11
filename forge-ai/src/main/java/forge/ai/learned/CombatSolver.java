package forge.ai.learned;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * <p>Phase 1: offline, parity-tested against the Python ({@code CombatSolverParityTest}). Phase 2 (2026-11-08): the
 * D22 v1 rules of {@code combat_search.py} d9230e6 (elim_no_swing, attack_rule d22_v1 with multi-block eligibility,
 * value_model d22 with the card-impact bonus), fixed (forced) attackers, top-N candidate lines; played online through
 * {@link LearnedCombat} / {@link CombatBoard}.
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
    /** NONE = every eligible creature may attack anyone (Python attack_rule 'none'); D22_V1 = per (creature, defender)
     * eligibility of the D22 addendum (Python attack_rule 'd22_v1', {@link #attackEligible}). */
    public enum AttackRule { NONE, D22_V1 }
    /** PT = max(p,0)+max(t,0) (+cmdValue); D22 = keyword proxy + card-impact threat bonus (Python value_model 'd22'). */
    public enum ValueModel { PT, D22 }
    /** Attack rule set on top of {@link AttackRule#D22_V1} (profile LEARNED_ATTACK_RULES). V1 = the D22 addendum of
     * 2026-11-08 as is. V1_1 = the four rules Mitchell approved 2026-11-10 (D22 addendum 20:25): (1) heads-up race with
     * a guard: when our race clock is not slower than the opponent's, the trade test is waived for unprotected
     * creatures (survival stays hard); (2) no lone early pokes: with two or more opponents alive and their life above
     * {@link Config#earlyLifeFrac} of their starting life, a line whose only attacker is one free creature is dropped
     * unless it deals {@link Config#pokeMinDmg}, kills, or does not raise the visible swing-back; (3) key defenders
     * (protected pieces that can block, without vigilance) attack only the top threat or a player the line kills;
     * (4) the threat first: against the top threat an unprotected creature may take an even trade (the defender's
     * value-best block costs it at least {@link Config#evenTradeFrac} of our loss and every killing block kills one of
     * theirs). The threat-vs-bystander case is not a rule (D40 judge model). */
    public enum RuleSet { V1, V1_1;
        public String tag() { return this == V1 ? "v1" : "v1.1"; }
        /** "v1.1" / "v11" / "v1_1" = V1_1; anything else = V1 */
        public static RuleSet parse(String s) {
            if (s == null) return V1;
            String t = s.trim().toLowerCase(Locale.ROOT);
            return t.equals("v1.1") || t.equals("v11") || t.equals("v1_1") ? V1_1 : V1;
        }
    }

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
        /** opponents eliminated by our combat do not swing back (Python elim_no_swing, d9230e6 default on) */
        public boolean elimNoSwing = true;
        public AttackRule attackRule = AttackRule.D22_V1;
        public ValueModel valueModel = ValueModel.D22;
        /** card-impact table for the D22 value model and the protected-piece rule (null: proxy value, commander only) */
        public CardImpact impact;
        public double impactScale = 40.0;
        /** largest multi-block the D22_V1 eligibility check enumerates (all legal blockers together are also tried) */
        public int maxBlock = 3;
        /** candidate lines kept in {@link Result#top} (chosen first) */
        public int topN = 5;
        /** D22_V1 applies to non-lethal attacks only: kill lines within the lethal scope may break it (see Ctx.admissible) */
        public boolean lethalExempt = true;
        /** trade test: the defender's protected blockers (its commander, top-decile impact) never count below their
         * proxy value, i.e. a below-median card-impact term does not discount them */
        public boolean protectedBlockerFloor = true;
        /** lethal exemption: protected groups also keep their unfiltered options with more than one opponent left when
         * every opponent could die together (Python protected_kill_all; necessary condition, see Ctx.killAllPossible).
         * admissible() still lets a protected breaker through only on a line that eliminates every opponent. */
        public boolean protectedKillAll = true;
        /** attack rule set (only with attackRule D22_V1); V1 = unchanged behaviour */
        public RuleSet rules = RuleSet.V1;
        /** v1.1 rule 2: "early" = two or more opponents alive and their summed life above this share of their summed
         * starting life (eliminated opponents count 0; the review-card "early" stratum) */
        public double earlyLifeFrac = 0.6;
        /** v1.1 rule 2: a lone early attacker is still allowed when the line deals at least this much player damage */
        public double pokeMinDmg = 5.0;
        /** v1.1 rule 4: an even trade into the top threat = their value lost under their value-best block at least
         * this share of ours */
        public double evenTradeFrac = 0.75;

        /** D22 preset (same as new Config()) */
        public static Config d22() { return new Config(); }

        /** D22 preset with the v1.1 rule set */
        public static Config d22v11() { Config c = new Config(); c.rules = RuleSet.V1_1; return c; }

        /** train/combat_search.py SearchParams() as of the phase-1 parity fixture (pre strict-kill fix, pre d9230e6) */
        public static Config python() {
            Config c = new Config();
            c.lifeFloor = 10.0; c.lethalScope = LethalScope.OFF; c.strictKill = false;
            c.elimNoSwing = false; c.attackRule = AttackRule.NONE; c.valueModel = ValueModel.PT;
            return c;
        }

        /** Python preset('d22_v1') of d9230e6: as d22() but lethal on the last opponent only (lethal_last) */
        public static Config pythonD22v1() {
            Config c = new Config();
            c.lethalScope = LethalScope.LAST; c.protectedKillAll = false;
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
        /** card name (card-impact lookup); null = unknown */
        public String name;
        /** protected piece set by the caller (on top of the commander / card-impact rule of the D22_V1 attack rule) */
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
        /** forced attackers (must-attack, goad): creature id -> the target it attacks; never moved or kept home, exempt
         * from the attack rule. A target that is not a living opponent / its planeswalker leaves the creature free. */
        public Map<Integer, Target> fixed = new LinkedHashMap<>();
        /** optional line to score as well (e.g. the stock declaration): attacker id -> target; see {@link Result#compare} */
        public Map<Integer, Target> compare;
        /** issue #28: per creature id, the targets it may not attack (a per-defender restriction such as "can't attack
         * a player it attacked this turn", or an attack cost there). Such a creature is its own group and its options
         * never send it there. Empty = every eligible creature may attack every target (Python). */
        public Map<Integer, Set<Target>> noAttack = new HashMap<>();
        /** issue #28: player-level attack requirements ("each opponent must attack with at least one creature each
         * combat if able", e.g. Seeker of Slaanesh): each set = at least one attacker must attack one of these targets.
         * Enforced when some eligible creature can attack there: the D22_V1 rule yields to it minimally (one
         * rule-breaking attacker per unmet requirement) and lines that miss it are dropped. Empty = Python. */
        public List<Set<Target>> mustAttackAny = new ArrayList<>();
        /** issue #28 parity (Python mustAttackCards): creatures that must attack, the defender left to the objective.
         * Each is its own group, eligible regardless of canAttack, with one option per target it is not banned from
         * (no home option), and gets no D22_V1 verdict. Not forced when banned from every target. {@link #fixed} wins.
         * Empty online (the hook fixes such creatures on the engine's defender instead). */
        public Set<Integer> mustAttack = new LinkedHashSet<>();
        /** v1.1 rule 2: summed starting life of every opponent at the table, eliminated ones included (0 = unknown:
         * the early test is off) */
        public int oppStartLife;
    }

    /** One attacker's target: a player seat, or a planeswalker (pwId >= 0) controlled by that seat. */
    public static final class Target {
        public final int seat, pwId;
        Target(int seat, int pwId) { this.seat = seat; this.pwId = pwId; }
        public static Target player(int seat) { return new Target(seat, -1); }
        public static Target planeswalker(int seat, int pwId) { return new Target(seat, pwId); }
        public boolean isPlaneswalker() { return pwId >= 0; }
        @Override public boolean equals(Object o) { return o instanceof Target && ((Target) o).seat == seat && ((Target) o).pwId == pwId; }
        @Override public int hashCode() { return seat * 31 + pwId; }
        @Override public String toString() { return pwId >= 0 ? "pw:" + pwId : Integer.toString(seat); }
    }

    /** One candidate line of the top-N list: the assignment, its profile and the lexicographic score tuple
     * (Python beam_key flattened: feasible, then per threat rank (kill, quantised eff), -load, -ownLost, oppKilled, -nAtt). */
    public static final class Line {
        public final Map<Integer, Target> attacks = new LinkedHashMap<>();
        public Profile profile;
        public double[] key;
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
        /** top-N candidate lines, the chosen line first, then the others by the beam key (descending) */
        public final List<Line> top = new ArrayList<>();
        /** per eligible creature: value, protected flag and, per target, the D22_V1 verdict ("ok:survives", "no:trade", ...) */
        public final Map<Integer, String[]> elig = new LinkedHashMap<>();
        public final Map<Integer, Double> value = new LinkedHashMap<>();
        public final Set<Integer> protectedIds = new HashSet<>();
        /** targets in solver order (players of living opponents, then their planeswalkers) */
        public final List<Target> targets = new ArrayList<>();
        /** fixed attackers whose target was honoured */
        public final Set<Integer> fixedIds = new HashSet<>();
        /** issue #28: per {@link Input#mustAttackAny} entry: 1 = enforced (some line meets it), 0 = no eligible creature
         * can meet it, -1 = enforceable but no candidate line met it (beam pruning; the caller's validity check decides) */
        public final List<Integer> reqState = new ArrayList<>();
        /** issue #28: no candidate line met every enforceable requirement (all lines were kept) */
        public boolean playerReqUnmet;
        /** issue #28: the {@link Input#mustAttack} creatures that were forced (eligible, not banned everywhere) */
        public final Set<Integer> forced = new TreeSet<>();
        /** chosen attackers that break the D22_V1 rule, excused by the line's kill (lethal exemption) */
        public final List<Integer> exempt = new ArrayList<>();
        /** {@link Input#compare} scored by the same objective (null when absent or not representable: an attacker the
         * solver does not know as eligible, or a target it does not know) */
        public Line compare;
        /** rule set the line was chosen under ("v1" / "v1.1") */
        public String rules = "v1";
        /** v1.1 rule 1 (heads-up only, else -1): turns for our all-out attack (through their blocks) to kill the
         * opponent, turns for their all-out swing (unblocked) to kill us (Integer.MAX_VALUE = never), race won */
        public int raceUs = -1, raceThem = -1;
        public boolean raceWon;
        /** v1.1 rule 2: the board counts as early */
        public boolean early;
        /** v1.1: candidate lines dropped by rule 2 (lone early poke) and rule 3 (key defender off home) */
        public int droppedPoke, droppedKey;

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
        String name;
        boolean cmd, tapped, sick, canAttack, canBlock, prot;
        /** value of this unit as a dead blocker in the D22_V1 trade test (NaN = value) */
        double tradeValue = Double.NaN;
        double tv() { return Double.isNaN(tradeValue) ? value : tradeValue; }
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
        u.id = c.id; u.seat = seat; u.p = c.p; u.t = c.t; u.dmg = c.dmg; u.name = c.name;
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

    // ------------------------------------------------------------------------------------------------ D22 v1 rules
    /** Python d22_value: p+t + keyword proxy (+cmdValue) + impactScale * (threat_shrunk - creature median), at least 0.5 */
    static double d22Value(Unit u, CardImpact imp, Config P) {
        double v = Math.max(u.p, 0) + Math.max(u.t, 0) + (u.cmd ? P.cmdValue : 0);
        v += b(u.fly) + b(u.fs) + b(u.tr) + b(u.ll) + b(u.vig) + b(u.menace) + b(u.reach) + b(u.unblk) + 2 * (b(u.dt) + b(u.ind))
                + (u.ds ? Math.max(u.p, 0) : 0);
        Double th = imp == null || u.name == null ? null : imp.threat(u.name);
        if (th != null) v += P.impactScale * (th - imp.median());
        return Math.max(0.5, v);
    }

    private static int b(boolean x) { return x ? 1 : 0; }

    /** Python multi_block: attacker a blocked by bl -> {a dies, blockers that die} (one blocker = exact duel). */
    static Object[] multiBlock(Unit a, List<Unit> bl) {
        if (bl.size() == 1) {
            boolean[] d = duel(a, bl.get(0));
            return new Object[]{d[0], d[1] ? List.of(bl.get(0)) : List.<Unit>of()};
        }
        int dmg = Math.max(a.p, 0) * (a.ds ? 2 : 1);
        List<Unit> dead = new ArrayList<>();
        List<Unit> order = new ArrayList<>(bl);
        order.sort((x, y) -> { int c = Double.compare(y.tv(), x.tv()); return c != 0 ? c : Integer.compare(x.id, y.id); });
        for (Unit b : order) {
            if (protectedFrom(b, a) || b.ind || dmg <= 0) continue;
            int need = a.dt ? 1 : Math.max(b.hp(), 0);
            if (need <= dmg) { dead.add(b); dmg -= need; }
        }
        boolean fsOnly = a.fs && !a.ds;
        int back = 0; boolean dt = false;
        for (Unit b : bl) {
            if (fsOnly && dead.contains(b) && !(b.fs || b.ds)) continue;
            if (protectedFrom(a, b) || b.p <= 0) continue;
            back += b.p * (b.ds ? 2 : 1); dt |= b.dt;
        }
        return new Object[]{!a.ind && (back >= a.hp() || dt), dead};
    }

    /** Python _blocks_of: blocks of lo..min(kmax, n) legal blockers (lo = 2 for menace), plus all of them if n > kmax */
    static List<List<Unit>> blocksOf(Unit a, List<Unit> legal, int kmax) {
        int lo = a.menace ? 2 : 1;
        List<List<Unit>> out = new ArrayList<>();
        for (int k = lo; k <= Math.min(kmax, legal.size()); k++) combos(legal, k, 0, new ArrayList<>(), out);
        if (legal.size() > kmax && legal.size() >= lo) out.add(new ArrayList<>(legal));
        return out;
    }

    private static void combos(List<Unit> src, int k, int from, List<Unit> cur, List<List<Unit>> out) {
        if (cur.size() == k) { out.add(new ArrayList<>(cur)); return; }
        for (int i = from; i < src.size(); i++) { cur.add(src.get(i)); combos(src, k, i + 1, cur, out); cur.remove(cur.size() - 1); }
    }

    /**
     * Python attack_eligible (attack_rule d22_v1): may {@code a} attack a defender whose available blockers are
     * {@code blockers}? Returns "ok:&lt;reason&gt;" or "no:&lt;reason&gt;". Protected pieces (commander, top-decile card
     * impact) only if no block (1..maxBlock blockers, or all legal blockers together) kills them; others if no single
     * legal blocker kills them, or the defender's best block costs them at least as much value as it costs us.
     */
    static String attackEligible(Unit a, List<Unit> blockers, Config P) { return attackEligible(a, blockers, P, false); }

    /** as above; {@code topThreat} (v1.1 rule 4, the defender is the top-ranked threat): an unprotected creature the
     * v1 trade test refuses may still attack on an even trade, "ok:threat-trade" (see {@link RuleSet}) */
    static String attackEligible(Unit a, List<Unit> blockers, Config P, boolean topThreat) {
        List<Unit> legal = new ArrayList<>();
        for (Unit x : blockers) if (canBlock(x, a)) legal.add(x);
        if (legal.isEmpty()) return "ok:unblockable";
        List<List<Unit>> blocks = blocksOf(a, legal, P.maxBlock);
        List<Object[]> res = new ArrayList<>(blocks.size());
        boolean anyKills = false;
        for (List<Unit> bl : blocks) { Object[] r = multiBlock(a, bl); res.add(r); anyKills |= (Boolean) r[0]; }
        if (a.prot) return anyKills ? "no:protected" : "ok:protected";
        boolean singleKills = false;
        for (Unit x : legal) singleKills |= duel(a, x)[0];
        if (!singleKills && (!a.menace || !anyKills)) return "ok:survives";
        double bo = 0, bt = 0;
        for (Object[] r : res) {
            double ours = (Boolean) r[0] ? a.value : 0, theirs = 0;
            @SuppressWarnings("unchecked") List<Unit> dead = (List<Unit>) r[1];
            for (Unit d : dead) theirs += d.tv();
            if (ours - theirs > bo - bt + 1e-9) { bo = ours; bt = theirs; }
        }
        if (bt >= bo - 1e-9) return "ok:trade";
        if (topThreat && P.rules == RuleSet.V1_1 && bt >= P.evenTradeFrac * bo - 1e-9) {
            // rule 4: even trade into the top threat; no block may kill ours for nothing
            boolean freeKill = false;
            for (Object[] r : res) if ((Boolean) r[0] && ((List<?>) r[1]).isEmpty()) { freeKill = true; break; }
            if (!freeKill) return "ok:threat-trade";
        }
        return "no:trade";
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
        /** per group: its fixed target index (forced attacker), or -1 */
        int[] fixedT;
        /** per group and target: D22_V1 verdict (null when the rule is off or the group is fixed) */
        String[][] verdict;
        final Set<Integer> fixedIds = new HashSet<>();
        /** issue #28: per creature id, its banned target indices (only bans on a current target count) */
        final Map<Integer, Set<Integer>> banT = new HashMap<>();
        /** issue #28: Input.mustAttack creatures that are forced here; per group: is it such a group */
        final Set<Integer> forced = new HashSet<>();
        boolean[] isForced;

        Ctx(Input in, Config P) {
            this.P = P; this.in = in;
            me = in.seats.get(0);
            if (me.seat != in.actingSeat) throw new IllegalArgumentException("seats[0] must be the acting seat");
            for (int i = 1; i < in.seats.size(); i++) { Seat s = in.seats.get(i); if (s.life > 0 && s.poison < 10) opps.add(s); }
            int smax = 1;
            for (Seat s : in.seats) {
                smax = Math.max(smax, s.seat); seatOf.put(s.seat, s);
                for (Creature c : s.creatures) {
                    Unit u = makeUnit(c, s.seat, P.cmdValue);
                    if (P.valueModel == ValueModel.D22) {
                        u.value = d22Value(u, P.impact, P);
                        u.prot = u.cmd || (P.impact != null && u.name != null && P.impact.isTop(u.name));
                        // coordinator 2026-11-08 b: an opponent's commander / high-impact blocker counts at least its
                        // proxy value in the trade test (a below-median impact term does not discount it)
                        if (P.protectedBlockerFloor && u.prot && s.seat != in.actingSeat)
                            u.tradeValue = Math.max(u.value, d22Value(u, null, P));
                    }
                    u.prot |= c.protectedPiece;
                    units.put(c.id, u);
                }
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
            Map<Integer, Integer> fixedTi = new HashMap<>();
            for (Map.Entry<Integer, Target> e : in.fixed.entrySet()) {
                int ti = targets.indexOf(e.getValue());
                if (ti >= 0) { fixedTi.put(e.getKey(), ti); fixedIds.add(e.getKey()); }
            }
            for (Map.Entry<Integer, Set<Target>> e : in.noAttack.entrySet()) {
                Set<Integer> ts = new TreeSet<>();
                for (int t = 0; t < T; t++) if (e.getValue().contains(targets.get(t))) ts.add(t);
                if (!ts.isEmpty()) banT.put(e.getKey(), ts);
            }
            for (Creature c : me.creatures)
                if (in.mustAttack.contains(c.id) && !fixedIds.contains(c.id) && banT.getOrDefault(c.id, Collections.emptySet()).size() < T) forced.add(c.id);
            for (Creature c : me.creatures) {
                mine.add(units.get(c.id));
                if (in.eligibility != null && !in.forcedEligible.contains(c.id) && !fixedIds.contains(c.id) && !forced.contains(c.id) && !in.eligibility.mayAttack(c, in)) vetoed.add(c.id);
            }
            // issue #28: a banned creature can attack some other target (canAttack is judged against one defender)
            for (Unit u : mine) if ((u.canAttack || in.forcedEligible.contains(u.id) || fixedIds.contains(u.id) || forced.contains(u.id) || banned(u.id)) && !u.tapped && !vetoed.contains(u.id)) eligible.add(u);
            for (Unit u : mine) if (u.tapped && (in.forcedEligible.contains(u.id) || fixedIds.contains(u.id) || forced.contains(u.id))) eligible.add(u);
            Set<Integer> elig = new HashSet<>();
            for (Unit u : eligible) elig.add(u.id);
            for (Unit u : mine) if (!elig.contains(u.id) && !u.tapped && u.canBlock) baseBlockers.add(u);
            Map<String, List<Unit>> g = new LinkedHashMap<>();
            for (Unit u : eligible) {
                String key = fixedIds.contains(u.id) ? "f" + u.id : forced.contains(u.id) ? "m" + u.id : banned(u.id) ? "n" + u.id : u.sig == null ? "c" + u.id
                        : P.valueModel == ValueModel.PT ? "s" + u.sig : "s" + u.sig + "|" + u.value + "|" + u.prot;
                g.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
            }
            groups.addAll(g.values());
            groups.sort((x, y) -> {
                Unit a = x.get(0), b = y.get(0);
                int c = Integer.compare(b.potential(), a.potential());
                if (c != 0) return c;
                c = Double.compare(b.value, a.value);
                return c != 0 ? c : Integer.compare(a.id, b.id);
            });
            groupBlocks = new boolean[groups.size()];
            fixedT = new int[groups.size()];
            isForced = new boolean[groups.size()];
            for (int i = 0; i < groups.size(); i++) {
                Unit u0 = groups.get(i).get(0);
                Integer ft = fixedTi.get(u0.id);
                fixedT[i] = ft == null ? -1 : ft;
                if (ft != null) { int[] v = new int[T]; v[ft] = 1; List<int[]> one = new ArrayList<>(); one.add(v); options.add(one); }
                else if (forced.contains(u0.id)) {   // issue #28: attacks exactly one target it is not banned from
                    isForced[i] = true;
                    Set<Integer> no = banT.getOrDefault(u0.id, Collections.emptySet());
                    List<int[]> opts = new ArrayList<>();
                    for (int t = 0; t < T; t++) if (!no.contains(t)) { int[] v = new int[T]; v[t] = 1; opts.add(v); }
                    options.add(opts);
                }
                else if (banned(u0.id)) {   // issue #28: never send it where it may not attack
                    Set<Integer> no = banT.get(u0.id);
                    List<int[]> keep = new ArrayList<>();
                    for (int[] v : groupOptions(groups.get(i).size())) {
                        boolean good = true;
                        for (int t = 0; t < T; t++) if (v[t] != 0 && no.contains(t)) { good = false; break; }
                        if (good) keep.add(v);
                    }
                    options.add(keep);
                }
                else options.add(groupOptions(groups.get(i).size()));
                groupBlocks[i] = u0.canBlock && !u0.tapped;
            }
            for (Set<Target> r : in.mustAttackAny) {
                boolean[] m = new boolean[T]; boolean any = false;
                for (int t = 0; t < T; t++) if (r.contains(targets.get(t))) { m[t] = true; any = true; }
                reqMask.add(any ? m : null);
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
            verdict = new String[groups.size()][];
            allowed = new boolean[groups.size()][];
            protGroup = new boolean[groups.size()];
            threats();   // pure (blockers, eligible, opponents' attackers); before the verdicts for v1.1 rule 4
            v11 = P.attackRule == AttackRule.D22_V1 && P.rules == RuleSet.V1_1;
            if (v11) {
                early = opps.size() >= 2 && in.oppStartLife > 0 && oppLife() > P.earlyLifeFrac * in.oppStartLife + 1e-9;
                if (opps.size() == 1) race();
            }
            if (P.attackRule == AttackRule.D22_V1) {
                // lethal exemption (coordinator 2026-11-08 b): the rule applies to non-lethal attacks only. When some
                // opponent could die this combat, groups keep their unfiltered options (protected groups only with one
                // opponent left) and solve() drops the lines that break the rule without a kill that excuses it.
                lethalExempt = P.lethalExempt && P.lethalScope != LethalScope.OFF && killPossible();
                boolean killAll = lethalExempt && P.protectedKillAll && killAllPossible();
                for (int gi = 0; gi < groups.size(); gi++) {
                    if (fixedT[gi] >= 0 || isForced[gi]) continue;   // forced: no verdict, never a rule breaker
                    Unit u0 = groups.get(gi).get(0);
                    protGroup[gi] = u0.prot;
                    String[] vs = new String[T];
                    boolean[] ok = new boolean[T];
                    for (int t = 0; t < T; t++) {
                        vs[t] = attackEligible(u0, oppBlk.get(targets.get(t).seat), P, v11 && targets.get(t).seat == rank.get(0));
                        // v1.1 rule 1: heads-up with the race won, unprotected creatures skip the trade test (the
                        // survival guard still decides how many stay home)
                        if (raceWon && !u0.prot && vs[t].startsWith("no")) vs[t] = "ok:race";
                        ok[t] = vs[t].startsWith("ok");
                    }
                    verdict[gi] = vs;
                    allowed[gi] = ok;
                    if (lethalExempt && (!u0.prot || opps.size() == 1 || killAll)) continue;
                    List<int[]> keep = new ArrayList<>();
                    for (int[] v : options.get(gi)) {
                        boolean good = true;
                        for (int t = 0; t < T; t++) if (v[t] != 0 && !ok[t]) { good = false; break; }
                        // issue #28: a single attacker into a required target stays available against the rule
                        if (!good && sum(v) == 1) for (int t = 0; t < T; t++) if (v[t] != 0 && inReq(t)) { good = true; break; }
                        if (good) keep.add(v);
                    }
                    options.set(gi, keep);
                }
            }
            for (boolean[] m : reqMask) {
                boolean can = false;
                if (m != null) for (List<int[]> os : options) for (int[] v : os) for (int t = 0; t < T && !can; t++) if (v[t] != 0 && m[t]) can = true;
                reqActive.add(can);
            }
        }

        /** v1.1 */
        boolean v11, early, raceWon;
        int raceUs = -1, raceThem = -1;

        private double oppLife() { double s = 0; for (Seat o : opps) s += Math.max(0, o.life); return s; }

        private static int turnsTo(double need, double perTurn) {
            if (need <= 0) return 0;
            if (perTurn <= 0) return Integer.MAX_VALUE;
            return (int) Math.ceil(need / perTurn - 1e-9);
        }

        /** v1.1 rule 1 (heads-up): our clock = every eligible creature attacking the opponent (fixed ones on their
         * target), damage and poison through the deterministic defender's blocks; their clock = every creature of
         * theirs that can attack next turn, unblocked. Race won = our clock is finite and not slower (we hit first). */
        private void race() {
            Seat o = opps.get(0);
            List<int[]> key = new ArrayList<>();
            for (int gi = 0; gi < groups.size(); gi++) {
                int t = fixedT[gi] >= 0 ? fixedT[gi] : 0;
                if (fixedT[gi] < 0 && banT.getOrDefault(groups.get(gi).get(0).id, Collections.emptySet()).contains(0)) continue;
                if (targets.get(t).isPlaneswalker()) continue;
                key.add(new int[]{gi, t, groups.get(gi).size()});
            }
            Sub all = makeSub(this, key, o.seat);
            raceUs = all.elim ? 1 : Math.min(turnsTo(o.life, all.dmg), turnsTo(10 - o.poison, all.ps));
            int d = 0, ps = 0;
            for (Unit u : oppAtt.get(o.seat)) { if (u.inf) ps += u.potential(); else d += u.potential(); }
            raceThem = Math.min(turnsTo(me.life, d), turnsTo(10 - me.poison, ps));
            raceWon = raceUs != Integer.MAX_VALUE && raceUs <= raceThem;
        }

        /** v1.1 rules 2 and 3 on a whole line: 0 = keep, 1 = lone early poke, 2 = key defender off home */
        int v11Drop(Profile p, Profile home) {
            int free = 0, lone = 0;
            for (int gi = 0; gi < groups.size(); gi++) {
                boolean bound = fixedT[gi] >= 0 || isForced[gi];
                for (int t = 0; t < T; t++) {
                    int n = p.asg[gi][t];
                    if (n == 0) continue;
                    if (bound) { lone = -1; continue; }
                    free += n;
                    Unit u0 = groups.get(gi).get(0);
                    if (u0.prot && groupBlocks[gi] && !u0.vig) {   // rule 3: a key defender
                        Target tg = targets.get(t);
                        boolean kill = !tg.isPlaneswalker() && p.elim.contains(tg.seat);
                        if (tg.seat != rank.get(0) && !kill) return 2;
                    }
                }
            }
            if (early && lone == 0 && free == 1 && p.elim.isEmpty()) {   // rule 2: one free creature alone, no kill
                double dmg = 0; for (double x : p.dmg.values()) dmg += x;
                if (dmg < P.pokeMinDmg - 1e-9 && p.load > home.load + 1e-9) return 1;
            }
            return 0;
        }

        /** issue #28: per mustAttackAny entry, its targets as a mask (null = none of them is a target here) and whether
         * some eligible group can attack there */
        final List<boolean[]> reqMask = new ArrayList<>();
        final List<Boolean> reqActive = new ArrayList<>();

        boolean banned(int id) { return banT.containsKey(id); }

        boolean hasReq() { return reqActive.contains(Boolean.TRUE); }

        private boolean inReq(int t) { for (boolean[] m : reqMask) if (m != null && m[t]) return true; return false; }

        /** every enforceable requirement is met by some attacker of p */
        boolean meetsReq(Profile p) {
            for (int r = 0; r < reqMask.size(); r++) {
                if (!reqActive.get(r)) continue;
                boolean hit = false;
                for (int gi = 0; gi < groups.size() && !hit; gi++) for (int t = 0; t < T; t++) if (p.asg[gi][t] != 0 && reqMask.get(r)[t]) { hit = true; break; }
                if (!hit) return false;
            }
            return true;
        }

        /** a line whose rule-breaking attackers are exactly what the unmet requirements need: each breaker attacks a
         * required target, and there are no more breakers than requirements the rule-abiding attackers leave unmet */
        private boolean excusedByReq(Profile p) {
            int breakers = 0;
            List<Integer> breakT = new ArrayList<>();
            boolean[] hitByAbiding = new boolean[reqMask.size()];
            for (int gi = 0; gi < groups.size(); gi++)
                for (int t = 0; t < T; t++) {
                    if (p.asg[gi][t] == 0) continue;
                    boolean breaks = allowed[gi] != null && !allowed[gi][t];
                    if (breaks) { breakers += p.asg[gi][t]; breakT.add(t); }
                    else for (int r = 0; r < reqMask.size(); r++) if (reqActive.get(r) && reqMask.get(r)[t]) hitByAbiding[r] = true;
                }
            if (breakers == 0) return true;
            int unmet = 0;
            for (int r = 0; r < reqMask.size(); r++) if (reqActive.get(r) && !hitByAbiding[r]) unmet++;
            if (breakers > unmet) return false;
            for (int t : breakT) {
                boolean need = false;
                for (int r = 0; r < reqMask.size(); r++) if (reqActive.get(r) && !hitByAbiding[r] && reqMask.get(r)[t]) need = true;
                if (!need) return false;
            }
            return true;
        }

        /** issue #28: admissible() with the requirement excuse (identical to admissible() without requirements) */
        boolean admissibleReq(Profile p) {
            if (!hasReq()) return admissible(p);
            if (lethalExempt && admissible(p)) return true;
            return excusedByReq(p);
        }

        /** per group and target: the D22_V1 verdict as a boolean (null = unrestricted: rule off or fixed group) */
        boolean[][] allowed;
        boolean[] protGroup;
        boolean lethalExempt;

        /** could any living opponent be eliminated by all our eligible attackers together (unblocked)? */
        private boolean killPossible() {
            int dmg = 0, inf = 0, cmd = 0;
            for (Unit u : eligible) { int p = u.potential(); if (u.inf) inf += p; else dmg += p; if (u.cmd) cmd += p; }
            for (Seat s : opps)
                if (dmg >= s.life || s.poison + inf >= 10 || (cmd > 0 && s.cmdDmgFromActing + cmd >= 21)) return true;
            return false;
        }

        /** could every living opponent die together (Python _kill_possible()[1]): one opponent left, or the total
         * potential covers what each opponent needs (life; 10 - poison with infect; 21 - commander damage with a
         * commander). A necessary condition only; admissible() checks the actual line. Call after killPossible(). */
        private boolean killAllPossible() {
            if (opps.size() == 1) return true;
            int dmg = 0, inf = 0, cmd = 0;
            for (Unit u : eligible) { int p = u.potential(); if (u.inf) inf += p; else dmg += p; if (u.cmd) cmd += p; }
            long need = 0;
            for (Seat s : opps) {
                int n = s.life;
                if (inf > 0) n = Math.min(n, 10 - s.poison);
                if (cmd > 0) n = Math.min(n, 21 - s.cmdDmgFromActing);
                need += n;
            }
            return dmg + inf >= need;
        }

        /** the lethal exemption: a line that breaks the attack rule is kept only if every rule-breaking attacker
         * attacks a player this line eliminates, and either every opponent dies (protected pieces allowed) or the
         * line is feasible and no rule breaker is protected. Lines that keep the rule are always admissible. */
        boolean admissible(Profile p) {
            if (!lethalExempt) return true;
            boolean all = p.elim.size() == opps.size();
            for (int gi = 0; gi < groups.size(); gi++) {
                if (allowed[gi] == null) continue;
                for (int t = 0; t < T; t++) {
                    if (p.asg[gi][t] == 0 || allowed[gi][t]) continue;
                    Target tg = targets.get(t);
                    if (tg.isPlaneswalker() || !p.elim.contains(tg.seat)) return false;
                    if (!all && (protGroup[gi] || !p.feasible)) return false;
                }
            }
            return true;
        }

        /** attackers of p that break the attack rule (excused by its kill) */
        List<Integer> exempted(Profile p) {
            List<Integer> out = new ArrayList<>();
            if (!lethalExempt) return out;
            for (int gi = 0; gi < groups.size(); gi++) {
                if (allowed[gi] == null) continue;
                List<Unit> g = groups.get(gi); int k = 0;
                for (int t = 0; t < T; t++)
                    for (int n = 0; n < p.asg[gi][t] && k < g.size(); n++, k++) if (!allowed[gi][t]) out.add(g.get(k).id);
            }
            return out;
        }

        /** the "nothing chosen" vector of group gi: all home, or its fixed target */
        int[] homeVec(int gi) {
            int[] v = new int[T];
            if (fixedT[gi] >= 0) v[fixedT[gi]] = 1;
            else if (isForced[gi]) return options.get(gi).get(0).clone();   // its first allowed target
            return v;
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

        int[][] allHome() { int[][] a = new int[groups.size()][]; for (int i = 0; i < a.length; i++) a[i] = homeVec(i); return a; }
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
                if (P.elimNoSwing && sub.elim) {   // d9230e6: an eliminated opponent does not swing back
                    pr.swing.put(s.seat, 0.0);
                    mx = any ? Math.max(mx, 0) : 0; any = true;
                    continue;
                }
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
                    if (P.elimNoSwing && sub.elim) continue;   // solo swing 0
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

    private static int[][] homeRest(Ctx ctx, int[][] partial, int G) {
        int[][] out = new int[G][];
        for (int i = 0; i < G; i++) out[i] = i < partial.length ? partial[i] : ctx.homeVec(i);
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
                for (int[][] e : ext) full.add(homeRest(ctx, e, G));
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
        if (ctx.lethalExempt || ctx.hasReq()) {   // drop rule-breaking lines that no kill / requirement excuses (all-home always stays)
            List<int[][]> c2 = new ArrayList<>(); List<Profile> p2 = new ArrayList<>();
            for (int i = 0; i < profs.size(); i++) if (ctx.admissibleReq(profs.get(i))) { c2.add(cands.get(i)); p2.add(profs.get(i)); }
            cands = c2; profs = p2;
        }
        int dropPoke = 0, dropKey = 0;
        if (ctx.v11 && !ctx.hasReq()) {   // v1.1 rules 2 and 3 (player-level requirements: v1 lines, see notes)
            Profile home = evaluate(ctx, List.<int[][]>of(ctx.allHome())).get(0);
            List<int[][]> c2 = new ArrayList<>(); List<Profile> p2 = new ArrayList<>();
            for (int i = 0; i < profs.size(); i++) {
                int d = ctx.v11Drop(profs.get(i), home);
                if (d == 1) dropPoke++; else if (d == 2) dropKey++;
                else { c2.add(cands.get(i)); p2.add(profs.get(i)); }
            }
            if (p2.isEmpty()) { c2.add(ctx.allHome()); p2.add(home); }   // cannot happen (all-home is kept), belt and braces
            cands = c2; profs = p2;
        }
        boolean reqMet = true;
        if (ctx.hasReq()) {   // issue #28: player-level attack requirements; keep every line if none meets them
            List<int[][]> c2 = new ArrayList<>(); List<Profile> p2 = new ArrayList<>();
            for (int i = 0; i < profs.size(); i++) if (ctx.meetsReq(profs.get(i))) { c2.add(cands.get(i)); p2.add(profs.get(i)); }
            if (!p2.isEmpty()) { cands = c2; profs = p2; } else reqMet = false;
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
        concrete(ctx, chosen.asg, res.attacks);
        // phase 2: top-N lines (chosen first, then by the beam key, distinct assignments) and per-creature verdicts
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < profs.size(); i++) order.add(i);
        final Config PK = P;
        final List<Profile> PR = profs;
        order.sort((x, y) -> compareBeamKey(PR.get(y), PR.get(x), PK));
        order.remove(Integer.valueOf(ci));
        order.add(0, ci);
        for (int i : order) {
            if (res.top.size() >= Math.max(1, P.topN)) break;
            Profile p = profs.get(i);
            boolean dup = false;
            for (Line l : res.top) if (sameAsg(l.profile.asg, p.asg)) { dup = true; break; }
            if (dup) continue;
            Line l = new Line(); l.profile = p; l.key = beamKey(p, P);
            concrete(ctx, p.asg, l.attacks);
            res.top.add(l);
        }
        res.targets.addAll(ctx.targets);
        res.fixedIds.addAll(ctx.fixedIds);
        for (int r = 0; r < ctx.reqMask.size(); r++) res.reqState.add(!ctx.reqActive.get(r) ? 0 : reqMet ? 1 : -1);
        res.playerReqUnmet = !reqMet;
        res.forced.addAll(ctx.forced);
        res.exempt.addAll(ctx.exempted(chosen));
        res.rules = ctx.v11 ? RuleSet.V1_1.tag() : RuleSet.V1.tag();
        res.raceUs = ctx.raceUs; res.raceThem = ctx.raceThem; res.raceWon = ctx.raceWon; res.early = ctx.early;
        res.droppedPoke = dropPoke; res.droppedKey = dropKey;
        if (in.compare != null) {
            int[][] asg = new int[G][ctx.T];
            int found = 0;
            for (int gi = 0; gi < G; gi++)
                for (Unit u : ctx.groups.get(gi)) {
                    Target t = in.compare.get(u.id);
                    if (t == null) continue;
                    int ti = ctx.targets.indexOf(t);
                    if (ti >= 0) { asg[gi][ti]++; found++; }
                }
            if (found == in.compare.size()) {
                Line l = new Line(); l.profile = evaluate(ctx, List.<int[][]>of(asg)).get(0); l.key = beamKey(l.profile, P);
                concrete(ctx, asg, l.attacks);
                res.compare = l;
            }
        }
        for (int gi = 0; gi < G; gi++)
            for (Unit u : ctx.groups.get(gi)) if (ctx.verdict[gi] != null) res.elig.put(u.id, ctx.verdict[gi]);
        for (Unit u : ctx.eligible) { res.value.put(u.id, u.value); if (u.prot) res.protectedIds.add(u.id); }
        return res;
    }

    /** group-count assignment -> attacker id -> target (units taken in global target order, as the Python output) */
    private static void concrete(Ctx ctx, int[][] asg, Map<Integer, Target> out) {
        for (int gi = 0; gi < ctx.groups.size(); gi++) {
            List<Unit> g = ctx.groups.get(gi); int k = 0;
            for (int t = 0; t < ctx.T; t++)
                for (int n = 0; n < asg[gi][t] && k < g.size(); n++, k++) out.put(g.get(k).id, ctx.targets.get(t));
        }
    }

    /** Python beam_key, flattened: feasible, (kill, q(eff)) per rank, -load, -ownLost, oppKilled, -nAtt */
    static double[] beamKey(Profile p, Config P) {
        int n = p.eff.length;
        double[] k = new double[1 + 2 * n + 4];
        k[0] = p.feasible ? 1 : 0;
        for (int i = 0; i < n; i++) {
            k[1 + 2 * i] = P.strictKill && rewarded(i, n, P) && p.elimByRank[i] ? 1 : 0;
            k[2 + 2 * i] = q(p.eff[i], P);
        }
        k[1 + 2 * n] = -p.load; k[2 + 2 * n] = -p.ownLost; k[3 + 2 * n] = p.oppKilled; k[4 + 2 * n] = -p.nAtt;
        return k;
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
