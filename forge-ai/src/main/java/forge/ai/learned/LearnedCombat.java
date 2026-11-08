package forge.ai.learned;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.AiController;
import forge.ai.AiProps;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * learned-ai D19/D22 phase 2: the attack declaration of a Learned seat played by {@link CombatSolver}.
 * <p>
 * Mode per seat: {@code -Dlearned.combat=<stock|solver>} (or the sim's {@code -combat}) when set, else the profile's
 * {@code LEARNED_COMBAT}; only seats whose profile has {@code LEARNED_HOOKS} on, so stock profiles never change.
 * The stock declaration runs first, unchanged (same randomness, same must-attack / goad handling); then
 * {@link #declare} rebuilds the board ({@link CombatBoard}), keeps the stock's forced attackers fixed on their stock
 * defenders, solves with the D22 preset (SUM_ALL, life floor 0 hard, lethal last always / another only if survival
 * holds, strict kill, elim_no_swing, D22_V1 eligibility, D22 value model with the {@code -impact} table if given),
 * replaces the declaration, and restores the stock one if Forge's validation rejects it. One {@code combat_attack}
 * record per declaration (when the learn log is open); the {@code attack_assign} fork forces candidate {@code pos}
 * (0..N-1 = the solver's top-N lines, N = the stock line).
 */
public final class LearnedCombat {
    private LearnedCombat() {}

    public static final String STOCK = "stock", SOLVER = "solver";
    /** sim / JVM override of every Learned seat's LEARNED_COMBAT (null = profile) */
    public static volatile String override = System.getProperty("learned.combat");
    private static volatile CardImpact impact;
    private static volatile String impactPath = System.getProperty("learned.impact");
    private static volatile boolean impactTried = false;

    public static void setOverride(String mode) { override = mode; }

    /** {@code -impact <path>}: the card-impact table (JSON from eval/card_impact.py, or CSV name,threat_shrunk) */
    public static synchronized void setImpactPath(String path) { impactPath = path; impact = null; impactTried = false; }

    static synchronized CardImpact impact() {
        if (!impactTried) {
            impactTried = true;
            if (impactPath != null && !impactPath.isEmpty()) {
                try {
                    impact = CardImpact.load(impactPath);
                    System.out.println("SIMNOTE learned combat impact " + impactPath + " " + impact.id() + " creatures=" + impact.size()
                            + " median=" + impact.median() + " cut90=" + impact.cut());
                } catch (Exception e) {
                    System.out.println("SIMNOTE learned combat impact " + impactPath + " could not be loaded (" + e.getMessage() + "); proxy value, protected = commander only");
                }
            }
        }
        return impact;
    }

    public static String mode(AiController aic) {
        if (!aic.getBoolProperty(AiProps.LEARNED_HOOKS)) return STOCK;
        String o = override;
        String m = o != null && !o.trim().isEmpty() ? o.trim() : aic.getProperty(AiProps.LEARNED_COMBAT);
        return SOLVER.equalsIgnoreCase(m) ? SOLVER : STOCK;
    }

    /** per game, per Learned seat: one SIMNOTE line naming the combat mode */
    static void announce(AiController aic, int seat) {
        String m = mode(aic);
        String src = override != null && !override.trim().isEmpty() ? "override" : "profile";
        StringBuilder sb = new StringBuilder("SIMNOTE learned seat=").append(seat).append(" combat=").append(m).append(" src=").append(src);
        if (SOLVER.equals(m)) {
            CardImpact imp = impact();
            sb.append(" preset=d22v1 (sum_all floor0 hard, lethal last/safe, strictKill, elimNoSwing, attackRule d22_v1, value d22)");
            sb.append(" impact=").append(imp == null ? "none (proxy value p+t+keywords; protected = commander only)" : imp.id());
        }
        System.out.println(sb);
    }

    static CombatSolver.Config config() {
        CombatSolver.Config c = CombatSolver.Config.d22();
        c.impact = impact();
        return c;
    }

    /**
     * Called by AiController.declareAttackers after the stock declaration (and its banding / Monte Carlo refinements)
     * is in {@code combat}. No-op unless the seat's combat mode is solver.
     */
    public static void declare(AiController aic, Player ai, Combat combat) {
        if (!SOLVER.equals(mode(aic))) return;
        CombatBoard board = CombatBoard.build(ai, combat);
        boolean anyCan = !board.stock.isEmpty();
        for (CombatSolver.Creature c : board.input.seats.get(0).creatures) anyCan |= c.canAttack && !c.tapped;
        if (!anyCan) return;   // nothing to decide: not a combat_attack decision
        int seat = board.input.actingSeat;
        int turn = ai.getGame().getPhaseHandler().getTurn();
        String status;
        CombatSolver.Result res = null;
        long t0 = System.nanoTime();
        Map<Integer, CombatSolver.Target> stockT = new LinkedHashMap<>();
        boolean stockRepresentable = true;
        for (Map.Entry<Card, GameEntity> e : board.stock.entrySet()) {
            CombatSolver.Target t = board.target(e.getValue());
            if (t == null) stockRepresentable = false; else stockT.put(e.getKey().getId(), t);
        }
        if (!board.exotic.isEmpty()) status = "fallback:" + board.exotic;
        else {
            if (stockRepresentable) board.input.compare = stockT;
            try {
                res = CombatSolver.solve(board.input, config());
                status = res == null ? "fallback:no-opponent" : "applied";
            } catch (RuntimeException ex) {
                System.err.println("learned-ai combat solver failed: " + ex);
                status = "fallback:error";
            }
        }
        long micros = (System.nanoTime() - t0) / 1000;
        int nTop = res == null ? 0 : res.top.size();
        int nCands = nTop + 1;   // + the stock line
        int forced = LearnedHooks.countDecision(LearnedHooks.ATTACK_ASSIGN, seat, nCands, turn);
        int chosen = res == null ? nTop : 0;
        if (forced >= 0) chosen = forced;
        List<String> dropped = new ArrayList<>();
        if (chosen < nTop) {
            Map<Card, GameEntity> want = new LinkedHashMap<>();
            for (Map.Entry<Integer, CombatSolver.Target> e : res.top.get(chosen).attacks.entrySet()) {
                Card c = board.cards.get(e.getKey());
                GameEntity d = board.defender(e.getValue());
                if (c == null || d == null || !CombatUtil.canAttack(c, d) || CombatUtil.getAttackCost(ai.getGame(), c, d) != null) {
                    dropped.add(String.valueOf(e.getKey()));
                    continue;
                }
                want.put(c, d);
            }
            apply(combat, want);
            if (!CombatUtil.validateAttackers(combat)) {
                apply(combat, board.stock);
                status = "invalid->stock";
            }
        } else if (res != null) {
            status = forced >= 0 ? "applied" : status;   // the stock line itself (fork pos N): combat already holds it
        }
        if (LearnLog.enabled()) LearnLog.raw("combat_attack", record(ai, board, res, status, chosen, nTop, forced >= 0, micros, dropped, combat, turn));
        if (forced >= 0) LearnedHooks.forkReseed();
    }

    private static void apply(Combat combat, Map<Card, GameEntity> attacks) {
        combat.clearAttackers();
        for (Map.Entry<Card, GameEntity> e : attacks.entrySet()) combat.addAttacker(e.getKey(), e.getValue());
    }

    // ---- record ---------------------------------------------------------------------------------------------------
    private static void num(StringBuilder sb, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) { sb.append("null"); return; }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append((long) v); else sb.append(Math.round(v * 1e4) / 1e4);
    }

    private static void attacks(StringBuilder sb, Map<Integer, CombatSolver.Target> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<Integer, CombatSolver.Target> e : m.entrySet()) {
            if (!first) sb.append(','); first = false;
            sb.append('"').append(e.getKey()).append("\":");
            if (e.getValue().isPlaneswalker()) sb.append("\"pw:").append(e.getValue().pwId).append('"'); else sb.append(e.getValue().seat);
        }
        sb.append('}');
    }

    private static void line(StringBuilder sb, CombatSolver.Line l, String src) {
        sb.append("{\"src\":\"").append(src).append("\",\"att\":");
        attacks(sb, l.attacks);
        sb.append(",\"key\":[");
        for (int i = 0; i < l.key.length; i++) { if (i > 0) sb.append(','); num(sb, l.key[i]); }
        CombatSolver.Profile p = l.profile;
        sb.append("],\"feasible\":").append(p.feasible ? 1 : 0).append(",\"load\":"); num(sb, p.load);
        sb.append(",\"lifeAfter\":"); num(sb, p.lifeAfter);
        sb.append(",\"ownLost\":"); num(sb, p.ownLost); sb.append(",\"oppKilled\":"); num(sb, p.oppKilled);
        sb.append(",\"nAtt\":").append(p.nAtt).append(",\"elim\":").append(p.elim.toString().replace(" ", ""));
        sb.append(",\"dmg\":{");
        boolean first = true;
        for (Map.Entry<Integer, Double> e : p.dmg.entrySet()) { if (!first) sb.append(','); first = false; sb.append('"').append(e.getKey()).append("\":"); num(sb, e.getValue()); }
        sb.append("}}");
    }

    static String record(Player ai, CombatBoard board, CombatSolver.Result res, String status, int chosen, int nTop,
                         boolean fork, long micros, List<String> dropped, Combat combat, int turn) {
        StringBuilder sb = new StringBuilder(4096);
        PhaseType ph = ai.getGame().getPhaseHandler().getPhase();
        sb.append("\"seat\":").append(board.input.actingSeat).append(",\"turn\":").append(turn).append(",\"phase\":").append(ph == null ? 0 : ph.ordinal());
        sb.append(",\"pilot\":\"").append(fork ? "fork" : "solver").append("\",\"model\":\"solver-d22v1\"");
        CardImpact imp = impact();
        sb.append(",\"impact\":\"").append(imp == null ? "proxy" : imp.id().replace("\"", "'")).append('"');
        sb.append(",\"status\":\"").append(status).append('"');
        sb.append(",\"mode\":").append(res == null ? "null" : "\"" + res.mode + "\"");
        sb.append(",\"fallback\":").append(res != null && res.fallback ? 1 : 0).append(",\"lethal\":").append(res != null && res.lethalOverride ? 1 : 0);
        sb.append(",\"nEval\":").append(res == null ? 0 : res.nEval).append(",\"nFeasible\":").append(res == null ? 0 : res.nFeasible);
        sb.append(",\"micros\":").append(micros);
        sb.append(",\"threat\":").append(res == null ? "[]" : res.threatRank.toString().replace(" ", ""));
        sb.append(",\"threatVal\":[");
        if (res != null) for (int i = 0; i < res.threatValue.size(); i++) { if (i > 0) sb.append(','); num(sb, res.threatValue.get(i)); }
        sb.append("],\"cands\":[");
        if (res != null) for (int i = 0; i < res.top.size(); i++) { if (i > 0) sb.append(','); line(sb, res.top.get(i), "solver"); }
        // the stock line is always the last candidate; scored when the solver could represent it
        if (res != null && res.top.size() > 0) sb.append(',');
        if (res != null && res.compare != null) line(sb, res.compare, "stock");
        else {
            Map<Integer, CombatSolver.Target> st = new LinkedHashMap<>();
            for (Map.Entry<Card, GameEntity> e : board.stock.entrySet()) { CombatSolver.Target t = board.target(e.getValue()); if (t != null) st.put(e.getKey().getId(), t); }
            sb.append("{\"src\":\"stock\",\"att\":"); attacks(sb, st); sb.append(",\"key\":null}");
        }
        sb.append("],\"chosen\":").append(chosen).append(",\"stock\":").append(nTop);
        sb.append(",\"fixed\":[");
        boolean first = true;
        for (Integer id : board.input.fixed.keySet()) { if (!first) sb.append(','); first = false; sb.append(id); }
        sb.append("],\"dropped\":[").append(String.join(",", dropped)).append(']');
        sb.append(",\"exempt\":").append(res == null ? "[]" : res.exempt.toString().replace(" ", ""));   // lethal exemption
        sb.append(",\"declared\":{");
        first = true;
        for (Card a : combat.getAttackers()) {
            if (a.getController() != ai) continue;
            if (!first) sb.append(','); first = false;
            sb.append('"').append(a.getId()).append("\":");
            GameEntity d = combat.getDefenderByAttacker(a);
            if (d instanceof Player) sb.append(LearnedHooks.seatNumber((Player) d));
            else if (d instanceof Card) sb.append("\"pw:").append(((Card) d).getId()).append('"');
            else sb.append("null");
        }
        sb.append("},\"elig\":[");
        first = true;
        for (CombatSolver.Creature c : board.input.seats.get(0).creatures) {
            if (res == null || !res.value.containsKey(c.id)) continue;   // eligible attackers only
            if (!first) sb.append(','); first = false;
            sb.append("{\"id\":").append(c.id).append(",\"v\":"); num(sb, res.value.get(c.id));
            sb.append(",\"prot\":").append(res.protectedIds.contains(c.id) ? 1 : 0).append(",\"cmd\":").append(c.commander ? 1 : 0);
            sb.append(",\"fixed\":").append(res.fixedIds.contains(c.id) ? 1 : 0).append(",\"t\":{");
            String[] v = res.elig.get(c.id);
            if (v != null) for (int t = 0; t < v.length; t++) {
                if (t > 0) sb.append(',');
                CombatSolver.Target tg = res.targets.get(t);
                sb.append('"').append(tg.isPlaneswalker() ? "pw:" + tg.pwId : String.valueOf(tg.seat)).append("\":\"").append(v[t]).append('"');
            }
            sb.append("}}");
        }
        sb.append(']');
        return sb.toString();
    }
}
