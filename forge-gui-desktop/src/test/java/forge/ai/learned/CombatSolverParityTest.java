package forge.ai.learned;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * learned-ai D19/D22: the Java attack solver returns the same assignment as the Python attack search
 * (train/combat_search.py, default SearchParams) on logged attackers snapshots. Fixture written by
 * forge-learned-ai/eval/combat_solver_dump.py: one line per snapshot {rec, src, py{chosen, mode, fallback, n_eval, rank}}.
 * Parity uses {@link CombatSolver.Config#python()} (the fixture's Python predates the kill-vs-1-short fix); strictKill
 * is checked to change only lines where it takes a top-threat kill the Python missed (or a beam reordering). The
 * D22 preset is smoke-checked to solve every row.
 */
public class CombatSolverParityTest {
    private static final String FIXTURE = "/forge/ai/learned/combat_solver_parity.jsonl.gz";

    static final class Row { String src; JsonObject rec, py; CombatSolver.Input in; }

    /** learned-ai D22: Python preset('d22_v1') aligned with Java d22() (py-data 2026-11-08 d, fork beb06b2305a);
     * no card-impact table (the rec carries no card names), so Config.d22() with impact null */
    private static final String FIXTURE_D22 = "/forge/ai/learned/combat_solver_parity-d22v1.jsonl.gz";

    static List<Row> load() throws Exception { return load(FIXTURE); }

    static List<Row> load(String fixture) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (InputStream raw = CombatSolverParityTest.class.getResourceAsStream(fixture)) {
            AssertJUnit.assertNotNull("fixture " + fixture, raw);
            BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty()) continue;
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                Row x = new Row(); x.src = o.get("src").getAsString(); x.rec = o.getAsJsonObject("rec"); x.py = o.getAsJsonObject("py");
                x.in = input(x.rec); rows.add(x);
            }
        }
        return rows;
    }

    static boolean b(JsonObject o, String k) { return o.get(k).getAsInt() != 0; }

    static CombatSolver.Input input(JsonObject rec) {
        CombatSolver.Input in = new CombatSolver.Input();
        in.actingSeat = rec.get("seat").getAsInt();
        for (JsonElement se : rec.getAsJsonArray("seats")) {
            JsonObject so = se.getAsJsonObject();
            CombatSolver.Seat s = new CombatSolver.Seat();
            s.seat = so.get("seat").getAsInt(); s.life = so.get("life").getAsInt(); s.poison = so.get("poison").getAsInt();
            s.cmdDmgFromActing = so.get("cmdDmgFromActing").getAsInt();
            for (JsonElement ce : so.getAsJsonArray("creatures")) {
                JsonObject co = ce.getAsJsonObject();
                CombatSolver.Creature c = new CombatSolver.Creature();
                c.id = co.get("id").getAsInt(); c.p = co.get("p").getAsInt(); c.t = co.get("t").getAsInt(); c.dmg = co.get("dmg").getAsInt();
                c.tapped = b(co, "tap"); c.sick = b(co, "sick"); c.commander = b(co, "cmd"); c.token = b(co, "tok");
                c.canAttack = b(co, "canAtk"); c.canBlock = b(co, "canBlk");
                for (JsonElement k : co.getAsJsonArray("kw")) c.kw.add(k.getAsString());
                if (co.has("kf")) c.kf = co.get("kf").getAsInt();
                if (co.has("pro")) c.pro = co.get("pro").getAsInt();
                if (co.has("col")) c.col = co.get("col").getAsInt();
                s.creatures.add(c);
            }
            for (JsonElement we : so.getAsJsonArray("pws")) {
                JsonObject wo = we.getAsJsonObject();
                s.pws.add(new CombatSolver.Planeswalker(wo.get("id").getAsInt(), wo.get("loy").getAsInt()));
            }
            in.seats.add(s);
        }
        for (JsonElement de : rec.getAsJsonArray("declared")) in.forcedEligible.add(de.getAsJsonObject().get("id").getAsInt());
        // schema 7 (issue #28): optional, absent in fixtures dumped before it (then both stay empty, as before)
        if (rec.has("mustAttackAny"))
            for (JsonElement r : rec.getAsJsonArray("mustAttackAny")) {
                java.util.Set<CombatSolver.Target> ts = new java.util.HashSet<>();
                for (JsonElement d : r.getAsJsonArray()) { CombatSolver.Target t = target(in, d); if (t != null) ts.add(t); }
                if (!ts.isEmpty()) in.mustAttackAny.add(ts);
            }
        if (rec.has("noAttack"))
            for (Map.Entry<String, JsonElement> e : rec.getAsJsonObject("noAttack").entrySet()) {
                java.util.Set<CombatSolver.Target> ts = new java.util.HashSet<>();
                for (JsonElement d : e.getValue().getAsJsonArray()) { CombatSolver.Target t = target(in, d); if (t != null) ts.add(t); }
                if (!ts.isEmpty()) in.noAttack.put(Integer.valueOf(e.getKey()), ts);
            }
        return in;
    }

    /** a snapshot defender (seat number or "pw:<id>") as a solver target; null if it is not on the board */
    static CombatSolver.Target target(CombatSolver.Input in, JsonElement d) {
        String s = d.getAsString();
        if (!s.startsWith("pw:")) return CombatSolver.Target.player(Integer.parseInt(s));
        int id = Integer.parseInt(s.substring(3));
        for (CombatSolver.Seat st : in.seats) for (CombatSolver.Planeswalker w : st.pws) if (w.id == id) return CombatSolver.Target.planeswalker(st.seat, id);
        return null;
    }

    static Map<String, String> expected(JsonObject py) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : py.getAsJsonObject("chosen").entrySet()) m.put(e.getKey(), e.getValue().getAsString());
        return m;
    }

    static Map<String, String> actual(CombatSolver.Result r) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : r.describe().entrySet()) m.put(e.getKey().toString(), e.getValue());
        return m;
    }

    @Test
    public void matchesPythonAttackSearch() throws Exception {
        List<Row> rows = load();
        AssertJUnit.assertTrue("fixture has >= 300 snapshots", rows.size() >= 300);
        CombatSolver.Config py = CombatSolver.Config.python();
        for (Row x : rows) CombatSolver.solve(x.in, py);   // warm-up (JIT)
        long[] ns = new long[rows.size()];
        int match = 0, modeMatch = 0, rankMatch = 0, fbMatch = 0; List<String> bad = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row x = rows.get(i);
            long t0 = System.nanoTime();
            CombatSolver.Result r = CombatSolver.solve(x.in, py);
            ns[i] = System.nanoTime() - t0;
            Map<String, String> want = expected(x.py), got = actual(r);
            List<Integer> rank = new ArrayList<>();
            for (JsonElement e : x.py.getAsJsonArray("rank")) rank.add(e.getAsInt());
            if (want.equals(got)) match++;
            else if (bad.size() < 10) bad.add(x.src + " g" + x.rec.get("game") + " t" + x.rec.get("turn") + " seat " + x.rec.get("seat")
                    + ": py " + want + " java " + got + " (" + r.mode + ", rank " + r.threatRank + " py " + rank + ")");
            if (x.py.get("mode").getAsString().equals(r.mode)) modeMatch++;
            if (x.py.get("fallback").getAsBoolean() == r.fallback) fbMatch++;
            if (rank.equals(r.threatRank)) rankMatch++;
        }
        long[] s = ns.clone(); Arrays.sort(s);
        System.out.printf("CombatSolver parity: %d/%d assignments match (mode %d, fallback %d, rank %d); solve p50 %.0f us, p99 %.0f us, max %.0f us%n",
                match, rows.size(), modeMatch, fbMatch, rankMatch, s[s.length / 2] / 1e3, s[(int) Math.min(s.length - 1, Math.ceil(s.length * 0.99) - 1)] / 1e3, s[s.length - 1] / 1e3);
        for (String m : bad) System.out.println("  mismatch " + m);
        AssertJUnit.assertEquals("assignments matching the Python solver", rows.size(), match);
    }

    static String where(Row x) { return x.src + " g" + x.rec.get("game") + " t" + x.rec.get("turn") + " seat " + x.rec.get("seat"); }

    static Set<String> idSet(JsonArray a) {
        Set<String> out = new TreeSet<>();
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    static Set<String> idSet(java.util.Collection<Integer> c) {
        Set<String> out = new TreeSet<>();
        for (Integer i : c) out.add(i.toString());
        return out;
    }

    /** Java verdicts in the fixture's shape {id: {defender: verdict}} */
    static Map<String, Map<String, String>> elig(CombatSolver.Result r) {
        Map<String, Map<String, String>> m = new TreeMap<>();
        for (Map.Entry<Integer, String[]> e : r.elig.entrySet()) {
            Map<String, String> d = new TreeMap<>();
            for (int t = 0; t < e.getValue().length; t++) d.put(r.targets.get(t).toString(), e.getValue()[t]);
            m.put(e.getKey().toString(), d);
        }
        return m;
    }

    static Map<String, Map<String, String>> elig(JsonObject py) {
        Map<String, Map<String, String>> m = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : py.getAsJsonObject("elig").entrySet()) {
            Map<String, String> d = new TreeMap<>();
            for (Map.Entry<String, JsonElement> v : e.getValue().getAsJsonObject().entrySet()) d.put(v.getKey(), v.getValue().getAsString());
            m.put(e.getKey(), d);
        }
        return m;
    }

    static boolean close(JsonArray a, List<Double> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (Math.abs(a.get(i).getAsDouble() - b.get(i)) > 1e-6) return false;
        return true;
    }

    static boolean close(JsonArray a, double[] b) {
        if (a.size() != b.length) return false;
        for (int i = 0; i < a.size(); i++) if (Math.abs(a.get(i).getAsDouble() - b[i]) > 1e-6) return false;
        return true;
    }

    /** the D22 preset (Config.d22()) against Python preset('d22_v1') on every field the fixture carries; the
     * decision fields (line, mode, fallback, lethal, rank, verdicts, exempt, protected) must all match */
    @Test
    public void matchesPythonD22v1Preset() throws Exception {
        List<Row> rows = load(FIXTURE_D22);
        AssertJUnit.assertTrue("d22v1 fixture has >= 300 snapshots", rows.size() >= 300);
        String[] fields = {"chosen", "mode", "fallback", "lethal", "rank", "threat", "n_eval", "n_feasible", "key",
                "elig", "exempt", "protected", "value", "top"};
        Map<String, Integer> ok = new LinkedHashMap<>();
        Map<String, String> example = new LinkedHashMap<>();
        for (String f : fields) ok.put(f, 0);
        for (Row x : rows) {
            CombatSolver.Result r = CombatSolver.solve(x.in, CombatSolver.Config.d22());
            JsonObject py = x.py;
            Map<String, String> want = expected(py), got = actual(r);
            List<Integer> rank = new ArrayList<>();
            for (JsonElement e : py.getAsJsonArray("rank")) rank.add(e.getAsInt());
            Map<String, Double> wantVal = new TreeMap<>(), gotVal = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : py.getAsJsonObject("value").entrySet()) wantVal.put(e.getKey(), e.getValue().getAsDouble());
            for (Map.Entry<Integer, Double> e : r.value.entrySet()) gotVal.put(e.getKey().toString(), e.getValue());
            boolean valOk = wantVal.keySet().equals(gotVal.keySet());
            if (valOk) for (String k : wantVal.keySet()) if (Math.abs(wantVal.get(k) - gotVal.get(k)) > 1e-6) valOk = false;
            List<Map<String, String>> wantTop = new ArrayList<>(), gotTop = new ArrayList<>();
            for (JsonElement e : py.getAsJsonArray("top")) {
                Map<String, String> m = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> a : e.getAsJsonObject().getAsJsonObject("chosen").entrySet()) m.put(a.getKey(), a.getValue().getAsString());
                wantTop.add(m);
            }
            for (CombatSolver.Line l : r.top) {
                Map<String, String> m = new LinkedHashMap<>();
                for (Map.Entry<Integer, CombatSolver.Target> a : l.attacks.entrySet()) m.put(a.getKey().toString(), a.getValue().toString());
                gotTop.add(m);
            }
            Map<String, Object[]> cmp = new LinkedHashMap<>();
            cmp.put("chosen", new Object[]{want.equals(got), want, got});
            cmp.put("mode", new Object[]{py.get("mode").getAsString().equals(r.mode), py.get("mode").getAsString(), r.mode});
            cmp.put("fallback", new Object[]{py.get("fallback").getAsBoolean() == r.fallback, py.get("fallback"), r.fallback});
            cmp.put("lethal", new Object[]{py.get("lethal").getAsBoolean() == r.lethalOverride, py.get("lethal"), r.lethalOverride});
            cmp.put("rank", new Object[]{rank.equals(r.threatRank), rank, r.threatRank});
            cmp.put("threat", new Object[]{close(py.getAsJsonArray("threat"), r.threatValue), py.get("threat"), r.threatValue});
            cmp.put("n_eval", new Object[]{py.get("n_eval").getAsInt() == r.nEval, py.get("n_eval"), r.nEval});
            cmp.put("n_feasible", new Object[]{py.get("n_feasible").getAsInt() == r.nFeasible, py.get("n_feasible"), r.nFeasible});
            cmp.put("key", new Object[]{close(py.getAsJsonArray("key"), r.top.get(0).key), py.get("key"), Arrays.toString(r.top.get(0).key)});
            Map<String, Map<String, String>> we = elig(py), ge = elig(r);
            cmp.put("elig", new Object[]{we.equals(ge), we, ge});
            Set<String> wx = idSet(py.getAsJsonArray("exempt")), gx = idSet(r.exempt);
            cmp.put("exempt", new Object[]{wx.equals(gx), wx, gx});
            Set<String> wp = idSet(py.getAsJsonArray("protected")), gp = idSet(r.protectedIds);
            cmp.put("protected", new Object[]{wp.equals(gp), wp, gp});
            cmp.put("value", new Object[]{valOk, wantVal, gotVal});
            cmp.put("top", new Object[]{wantTop.equals(gotTop), wantTop, gotTop});
            for (Map.Entry<String, Object[]> e : cmp.entrySet()) {
                Object[] v = e.getValue();
                if ((Boolean) v[0]) ok.merge(e.getKey(), 1, Integer::sum);
                else example.putIfAbsent(e.getKey(), where(x) + ": py " + v[1] + " java " + v[2]
                        + " (mode " + r.mode + ", lethal " + r.lethalOverride + ")");
            }
        }
        StringBuilder sb = new StringBuilder("CombatSolver d22v1 parity (" + rows.size() + " rows):");
        for (String f : fields) sb.append(' ').append(f).append(' ').append(ok.get(f));
        System.out.println(sb);
        for (Map.Entry<String, String> e : example.entrySet()) System.out.println("  mismatch " + e.getKey() + ": " + e.getValue());
        int kaLine = 0, kaMode = 0;   // what protectedKillAll changes on this fixture (py-data: 4 lines, 6 modes)
        for (Row x : rows) {
            CombatSolver.Config c = CombatSolver.Config.d22(); c.protectedKillAll = false;
            CombatSolver.Result a = CombatSolver.solve(x.in, CombatSolver.Config.d22()), b = CombatSolver.solve(x.in, c);
            if (!actual(a).equals(actual(b))) kaLine++;
            if (!a.mode.equals(b.mode)) kaMode++;
        }
        System.out.printf("CombatSolver protectedKillAll off: %d lines and %d modes differ from d22()%n", kaLine, kaMode);
        AssertJUnit.assertTrue("protectedKillAll is exercised by the fixture", kaLine + kaMode > 0);
        for (String f : new String[]{"chosen", "mode", "fallback", "lethal", "rank", "elig", "exempt", "protected"})
            AssertJUnit.assertEquals("d22v1 field " + f + " matching Python", rows.size(), (int) ok.get(f));
    }

    @Test
    public void d22PresetSolvesEveryRowAndEligibilityHookVetoes() throws Exception {
        int n = 0, feasibleOrLethal = 0, feasibleExpected = 0;
        for (Row x : load()) {
            CombatSolver.Result r = CombatSolver.solve(x.in, CombatSolver.Config.d22());
            AssertJUnit.assertNotNull(r); n++;
            if (!r.fallback || r.lethalOverride) feasibleOrLethal++;
            for (CombatSolver.SwingEstimator est : CombatSolver.SwingEstimator.values()) {
                CombatSolver.Config c = CombatSolver.Config.d22(); c.swingEstimator = est;
                for (CombatSolver.Seat st : x.in.seats) x.in.attackProb.put(st.seat, 0.3);
                AssertJUnit.assertNotNull(CombatSolver.solve(x.in, c));
                if (est == CombatSolver.SwingEstimator.EXPECTED && !CombatSolver.solve(x.in, c).fallback) feasibleExpected++;
            }
            x.in.eligibility = (c, board) -> false;   // veto everything not logged as declared
            CombatSolver.Result v = CombatSolver.solve(x.in, CombatSolver.Config.d22());
            for (Integer id : v.attacks.keySet()) AssertJUnit.assertTrue(x.in.forcedEligible.contains(id));
        }
        System.out.printf("CombatSolver D22 preset: %d rows, %d with a feasible (or lethal) line; EXPECTED(p=0.3) feasible %d%n", n, feasibleOrLethal, feasibleExpected);
    }

    /** D22 lethal exemption: how often a killable top threat is killed (killable = some line kills it with the rule off) */
    @Test
    public void lethalExemptionTakesKillableTopThreats() throws Exception {
        int killable = 0, on = 0, off = 0;
        for (Row x : load()) {
            CombatSolver.Config free = CombatSolver.Config.d22(); free.attackRule = CombatSolver.AttackRule.NONE;
            CombatSolver.Result f = CombatSolver.solve(x.in, free);
            if (f == null || f.threatRank.isEmpty() || !f.chosen.elim.contains(f.threatRank.get(0))) continue;
            killable++;
            CombatSolver.Result a = CombatSolver.solve(x.in, CombatSolver.Config.d22());
            CombatSolver.Config c = CombatSolver.Config.d22(); c.lethalExempt = false;
            CombatSolver.Result b = CombatSolver.solve(x.in, c);
            if (a.chosen.elim.contains(a.threatRank.get(0))) on++;
            if (b.chosen.elim.contains(b.threatRank.get(0))) off++;
        }
        System.out.printf("CombatSolver lethal exemption: top threat killed in %d/%d killable rows (without the exemption %d)%n", on, killable, off);
        AssertJUnit.assertTrue(on >= off);
    }

    /** strictKill (default) may change a line only to take an elimination of threat #1 that the Python line missed */
    @Test
    public void strictKillOnlyAddsKills() throws Exception {
        CombatSolver.Config off = CombatSolver.Config.python();
        CombatSolver.Config on = CombatSolver.Config.python(); on.strictKill = true;
        int changed = 0, kills = 0, beam = 0;
        for (Row x : load()) {
            CombatSolver.Result a = CombatSolver.solve(x.in, off), b = CombatSolver.solve(x.in, on);
            if (actual(a).equals(actual(b))) continue;
            changed++;
            boolean bKill = b.chosen.elimByRank[0], aKill = a.chosen.elimByRank[0];
            if (bKill && !aKill) kills++;
            else if ("beam".equals(b.mode)) beam++;   // the kill flag also reorders the beam, so pruning can differ
            else System.out.println("  strictKill changed a line without a top-threat kill: " + x.src + " g" + x.rec.get("game") + " t" + x.rec.get("turn"));
        }
        System.out.printf("CombatSolver strictKill: %d lines changed, %d take a top-threat kill the Python left alive, %d beam reorderings%n", changed, kills, beam);
        AssertJUnit.assertEquals(changed, kills + beam);
    }
}
