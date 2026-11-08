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
import java.util.zip.GZIPInputStream;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

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

    static List<Row> load() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (InputStream raw = CombatSolverParityTest.class.getResourceAsStream(FIXTURE)) {
            AssertJUnit.assertNotNull("fixture " + FIXTURE, raw);
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
        return in;
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
