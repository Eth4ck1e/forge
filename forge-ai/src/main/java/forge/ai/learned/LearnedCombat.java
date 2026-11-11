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
 * <p>
 * Shadow mode ({@code shadow}, phase 2c): the stock declaration stands; the solver still runs on the same board and
 * the record is written with {@code "status":"shadow"}, {@code "pilot":"stock"} and {@code chosen} = the stock
 * line's position. Nothing in the game changes (the solver and the board builder consume no randomness and touch no
 * game state). Decisions are counted as in solver mode, so an {@code attack_assign} fork on a shadow seat forces
 * the solver line at that record (pilot fork) and the game continues from there.
 */
public final class LearnedCombat {
    private LearnedCombat() {}

    public static final String STOCK = "stock", SOLVER = "solver", SHADOW = "shadow";
    /** sim / JVM override of every Learned seat's LEARNED_COMBAT (null = profile) */
    public static volatile String override = System.getProperty("learned.combat");
    private static volatile CardImpact impact;
    private static volatile String impactPath = System.getProperty("learned.impact");
    private static volatile boolean impactTried = false;

    public static void setOverride(String mode) { override = mode; }

    /** sim / JVM override of every Learned seat's LEARNED_ATTACK_RULES (v1 | v1.1; null = profile) */
    public static volatile String rulesOverride = System.getProperty("learned.attackRules");

    public static void setRulesOverride(String rules) { rulesOverride = rules; }

    /** D22 v1.1 (2026-11-10): the seat's attack rule set (only used when its combat mode is solver or shadow) */
    public static CombatSolver.RuleSet rules(AiController aic) {
        String o = rulesOverride;
        return CombatSolver.RuleSet.parse(o != null && !o.trim().isEmpty() ? o : aic.getProperty(AiProps.LEARNED_ATTACK_RULES));
    }

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
        if (SOLVER.equalsIgnoreCase(m)) return SOLVER;
        return SHADOW.equalsIgnoreCase(m) ? SHADOW : STOCK;
    }

    /** per game, per Learned seat: one SIMNOTE line naming the combat mode */
    static void announce(AiController aic, int seat) {
        String m = mode(aic);
        String src = override != null && !override.trim().isEmpty() ? "override" : "profile";
        StringBuilder sb = new StringBuilder("SIMNOTE learned seat=").append(seat).append(" combat=").append(m).append(" src=").append(src);
        if (SOLVER.equals(m) || SHADOW.equals(m)) {
            CardImpact imp = impact();
            sb.append(" preset=d22v1 (sum_all floor0 hard, lethal last/safe, strictKill, elimNoSwing, attackRule d22_v1, value d22)");
            sb.append(" rules=").append(rules(aic).tag());
            sb.append(" impact=").append(imp == null ? "none (proxy value p+t+keywords; protected = commander only)" : imp.id());
        }
        System.out.println(sb);
    }

    /** the D22 preset with the -impact table, rule set v1 */
    static CombatSolver.Config config() {
        CombatSolver.Config c = CombatSolver.Config.d22();
        c.impact = impact();
        return c;
    }

    static CombatSolver.Config config(AiController aic) {
        CombatSolver.Config c = CombatSolver.Config.d22();
        c.impact = impact();
        c.rules = rules(aic);
        return c;
    }

    /**
     * Called by AiController.declareAttackers after the stock declaration (and its banding / Monte Carlo refinements)
     * is in {@code combat}. No-op unless the seat's combat mode is solver.
     */
    public static void declare(AiController aic, Player ai, Combat combat) {
        String m = mode(aic);
        boolean shadow = SHADOW.equals(m);
        if (!SOLVER.equals(m) && !shadow) return;
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
                res = CombatSolver.solve(board.input, config(aic));
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
        int chosen = res == null || shadow ? nTop : 0;
        if (forced >= 0) chosen = forced;
        List<String> dropped = new ArrayList<>();
        // issue #28: every line is checked against the engine's own acceptance test before it is applied (read-only:
        // AttackConstraints.countViolations vs the best legal attack); a rejected solver line gives way to the next
        // solver line in rank order (not on a fork: the forced position stands or falls), then to the stock line.
        List<String> rejected = new ArrayList<>();
        String fallbackReason = null;
        int[] bestV = {Integer.MIN_VALUE};
        if (chosen < nTop) {
            List<Integer> order = new ArrayList<>();
            order.add(chosen);
            if (forced < 0) for (int k = 0; k < nTop; k++) if (k != chosen) order.add(k);
            boolean done = false;
            for (int k : order) {
                List<String> drop = new ArrayList<>();
                Map<Card, GameEntity> want = want(ai, board, res.top.get(k), drop);
                String why = rejection(combat, want, bestV);
                if (!why.isEmpty()) { rejected.add("{\"pos\":" + k + ",\"why\":\"" + why + "\"}"); if (fallbackReason == null) fallbackReason = why; continue; }
                apply(combat, want);
                if (!CombatUtil.validateAttackers(combat)) {   // belt and braces: the live test disagrees
                    rejected.add("{\"pos\":" + k + ",\"why\":\"live-invalid\"}");
                    if (fallbackReason == null) fallbackReason = "live-invalid";
                    continue;
                }
                chosen = k; dropped = drop; done = true;
                break;
            }
            if (!done) {
                apply(combat, board.stock);
                status = "invalid->stock";
                String sw = rejection(combat, board.stock, bestV);
                if (!sw.isEmpty()) fallbackReason += "|stock:" + sw;   // the stock line is invalid too: AiController repairs it
                chosen = nTop;
            }
        } else if (res != null) {
            // the stock line itself (fork pos N, or shadow mode): combat already holds it
            status = forced >= 0 ? "applied" : shadow ? "shadow" : status;
            if (shadow && nTop > 0) {   // what solver mode would have met (nothing is applied)
                String why = rejection(combat, want(ai, board, res.top.get(0), new ArrayList<>()), bestV);
                if (!why.isEmpty()) { rejected.add("{\"pos\":0,\"why\":\"" + why + "\"}"); fallbackReason = why; }
            }
        } else if (status.startsWith("fallback:")) {
            fallbackReason = status.substring("fallback:".length());
        }
        if (LearnLog.enabled()) {
            final String st = status; final int ch = chosen; final CombatSolver.Result r = res;   // the record never changes play
            final List<String> dr = dropped; final String fr = fallbackReason;
            LearnLog.raw("combat_attack", LearnedHooks.transparently(ai, LearnedHooks.ATTACK_TARGET,
                    () -> record(ai, board, r, st, ch, nTop, forced >= 0 ? "fork" : shadow ? "stock" : "solver", micros, dr, combat, turn, fr, rejected)));
        }
        if (forced >= 0) LearnedHooks.forkReseed();
    }

    /** a solver line as live attacks; attackers it may not send there (should not happen since issue #28) go to drop */
    private static Map<Card, GameEntity> want(Player ai, CombatBoard board, CombatSolver.Line line, List<String> drop) {
        Map<Card, GameEntity> want = new LinkedHashMap<>();
        for (Map.Entry<Integer, CombatSolver.Target> e : line.attacks.entrySet()) {
            Card c = board.cards.get(e.getKey());
            GameEntity d = board.defender(e.getValue());
            if (c == null || d == null || !CombatUtil.canAttack(c, d) || CombatUtil.getAttackCost(ai.getGame(), c, d) != null) {
                drop.add(String.valueOf(e.getKey()));
                continue;
            }
            want.put(c, d);
        }
        return want;
    }

    /** CombatBoard.rejection with the engine's best legal attack computed once per declaration */
    private static String rejection(Combat combat, Map<Card, GameEntity> attacks, int[] best) {
        if (best[0] == Integer.MIN_VALUE) best[0] = combat.getAttackConstraints().getLegalAttackers().getRight();
        return CombatBoard.rejection(combat, attacks, best[0]);
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
                         String pilot, long micros, List<String> dropped, Combat combat, int turn,
                         String fallbackReason, List<String> rejected) {
        StringBuilder sb = new StringBuilder(4096);
        PhaseType ph = ai.getGame().getPhaseHandler().getPhase();
        sb.append("\"seat\":").append(board.input.actingSeat).append(",\"turn\":").append(turn).append(",\"phase\":").append(ph == null ? 0 : ph.ordinal());
        String rs = res != null ? res.rules : "v1";
        sb.append(",\"pilot\":\"").append(pilot).append("\",\"model\":\"").append("v1".equals(rs) ? "solver-d22v1" : "solver-d22v1.1").append('"');
        // schema 10 (D22 v1.1): the attack rule set and, under v1.1, the rule inputs the Python mirror needs
        sb.append(",\"rules\":\"").append(rs).append('"');
        if (res != null && !"v1".equals(rs)) {
            sb.append(",\"v11\":{\"oppStartLife\":").append(board.input.oppStartLife).append(",\"early\":").append(res.early ? 1 : 0)
              .append(",\"raceUs\":").append(res.raceUs == Integer.MAX_VALUE ? "null" : String.valueOf(res.raceUs))
              .append(",\"raceThem\":").append(res.raceThem == Integer.MAX_VALUE ? "null" : String.valueOf(res.raceThem))
              .append(",\"raceWon\":").append(res.raceWon ? 1 : 0)
              .append(",\"dropPoke\":").append(res.droppedPoke).append(",\"dropKey\":").append(res.droppedKey).append('}');
        }
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
        // issue #28 (schema 6): why a line was not used, forced attackers the stock left out, requirement state
        sb.append(",\"fallbackReason\":").append(fallbackReason == null ? "null" : "\"" + fallbackReason.replace("\"", "'") + "\"");
        sb.append(",\"rejected\":[").append(String.join(",", rejected)).append(']');
        sb.append(",\"addedForced\":").append(board.addedForced.toString().replace(" ", ""));
        sb.append(",\"banned\":").append(board.input.noAttack.keySet().toString().replace(" ", ""));
        sb.append(",\"playerReq\":").append(res == null ? "[]" : res.reqState.toString().replace(" ", ""));
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
        // schema 12 (D45): roles of every card on the solver board (creatures and planeswalkers, face-down ones without
        // roles), battlefield role counts of every living seat on the board
        CardRoles.rolesJson(sb, board.cards.values());
        CardRoles.roleCountsJson(sb, board.players.values());
        return sb.toString();
    }
}
