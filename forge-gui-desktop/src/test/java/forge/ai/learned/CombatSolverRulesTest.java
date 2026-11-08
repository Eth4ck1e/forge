package forge.ai.learned;

import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/** learned-ai D22 phase 2: the v1 attack rules of the solver (trade rule, protected pieces, survival, fixed attackers). */
public class CombatSolverRulesTest {

    static CombatSolver.Creature cr(int id, String name, int p, int t, boolean cmd, boolean tapped) {
        CombatSolver.Creature c = new CombatSolver.Creature();
        c.id = id; c.name = name; c.p = p; c.t = t; c.commander = cmd; c.tapped = tapped;
        c.canAttack = !tapped; c.canBlock = !tapped; c.kf = 0; c.pro = 0; c.col = 16;
        return c;
    }

    static CombatSolver.Seat seat(int n, int life, CombatSolver.Creature... cs) {
        CombatSolver.Seat s = new CombatSolver.Seat();
        s.seat = n; s.life = life;
        for (CombatSolver.Creature c : cs) s.creatures.add(c);
        return s;
    }

    static CombatSolver.Input board(CombatSolver.Seat... seats) {
        CombatSolver.Input in = new CombatSolver.Input();
        in.actingSeat = seats[0].seat;
        for (CombatSolver.Seat s : seats) in.seats.add(s);
        return in;
    }

    @Test
    public void beastAttacksIntoCommanderAndCats() {
        CombatSolver.Input in = board(seat(1, 40, cr(1, "Beast", 5, 5, false, false)),
                seat(2, 40, cr(10, "Cmdr", 3, 3, true, false), cr(11, "Cat", 2, 2, false, false), cr(12, "Cat", 2, 2, false, false)));
        CombatSolver.Result r = CombatSolver.solve(in, CombatSolver.Config.d22());
        AssertJUnit.assertEquals("beast attacks seat 2", "2", String.valueOf(r.attacks.get(1)));
        AssertJUnit.assertEquals("ok:survives", r.elig.get(1)[0]);
        // the triple block kills the beast but costs them the commander and a cat: still a positive trade
        CombatSolver.Config P = CombatSolver.Config.d22();
        CombatSolver.Unit beast = CombatSolver.makeUnit(cr(1, "Beast", 5, 5, false, false), 1, P.cmdValue);
        beast.value = CombatSolver.d22Value(beast, null, P);
        java.util.List<CombatSolver.Unit> bl = new java.util.ArrayList<>();
        for (CombatSolver.Creature c : in.seats.get(1).creatures) { CombatSolver.Unit u = CombatSolver.makeUnit(c, 2, P.cmdValue); u.value = CombatSolver.d22Value(u, null, P); bl.add(u); }
        Object[] tri = CombatSolver.multiBlock(beast, bl);
        AssertJUnit.assertTrue("triple block kills the beast", (Boolean) tri[0]);
        AssertJUnit.assertEquals("beast kills the commander and a cat", 2, ((java.util.List<?>) tri[1]).size());
    }

    @Test
    public void beastStaysHomeAgainstABiggerBlocker() {
        CombatSolver.Input in = board(seat(1, 40, cr(1, "Beast", 5, 5, false, false)),
                seat(2, 40, cr(10, "Wurm", 6, 6, false, false)));
        CombatSolver.Result r = CombatSolver.solve(in, CombatSolver.Config.d22());
        AssertJUnit.assertEquals("no:trade", r.elig.get(1)[0]);
        AssertJUnit.assertTrue(r.attacks.isEmpty());
    }

    @Test
    public void commanderDoesNotAttackIntoKillingDoubleBlock() {
        CombatSolver.Input in = board(seat(1, 40, cr(1, "Our Commander", 4, 4, true, false), cr(2, "Bear Lord", 4, 4, false, false)),
                seat(2, 40, cr(11, "Cat", 2, 2, false, false), cr(12, "Cat", 2, 2, false, false)));
        CombatSolver.Result r = CombatSolver.solve(in, CombatSolver.Config.d22());
        AssertJUnit.assertEquals("no:protected", r.elig.get(1)[0]);
        AssertJUnit.assertEquals("ok:survives", r.elig.get(2)[0]);
        AssertJUnit.assertFalse("commander stays home", r.attacks.containsKey(1));
        AssertJUnit.assertTrue("the same body without the flag attacks", r.attacks.containsKey(2));
        AssertJUnit.assertTrue(r.protectedIds.contains(1));
        // a caller-flagged protected piece (card-impact top decile) behaves like the commander
        in.seats.get(0).creatures.get(1).protectedPiece = true;
        r = CombatSolver.solve(in, CombatSolver.Config.d22());
        AssertJUnit.assertTrue(r.attacks.isEmpty());
    }

    @Test
    public void survivalStopsAlphaStrike() {
        CombatSolver.Input in = board(seat(1, 5, cr(1, "Knight", 3, 3, false, false), cr(2, "Knight", 3, 3, false, false), cr(3, "Knight", 3, 3, false, false)),
                seat(2, 40, cr(11, "Goblin", 2, 2, false, true), cr(12, "Goblin", 2, 2, false, true), cr(13, "Goblin", 2, 2, false, true)));
        Map<Integer, CombatSolver.Target> alpha = new java.util.LinkedHashMap<>();
        for (int id = 1; id <= 3; id++) alpha.put(id, CombatSolver.Target.player(2));
        in.compare = alpha;
        CombatSolver.Result r = CombatSolver.solve(in, CombatSolver.Config.d22());
        AssertJUnit.assertEquals("two attack, one stays home to survive the swing-back", 2, r.attacks.size());
        AssertJUnit.assertTrue(r.chosen.feasible);
        AssertJUnit.assertNotNull(r.compare);
        AssertJUnit.assertFalse("the alpha strike leaves us dead to the visible swing", r.compare.profile.feasible);
        AssertJUnit.assertTrue("top-N: chosen first", r.top.get(0).profile == r.chosen);
        // a forced attacker is kept on its target
        in.compare = null;
        in.fixed.put(3, CombatSolver.Target.player(2));
        r = CombatSolver.solve(in, CombatSolver.Config.d22());
        AssertJUnit.assertTrue(r.attacks.containsKey(3));
        AssertJUnit.assertEquals(2, r.attacks.size());
        AssertJUnit.assertTrue(r.fixedIds.contains(3));
    }

    @Test
    public void lethalOnLastOpponentIgnoresSwingBack() {
        CombatSolver.Input in = board(seat(1, 5, cr(1, "Knight", 3, 3, false, false), cr(2, "Knight", 3, 3, false, false), cr(3, "Knight", 3, 3, false, false)),
                seat(2, 9, cr(11, "Goblin", 2, 2, false, true), cr(12, "Goblin", 2, 2, false, true), cr(13, "Goblin", 2, 2, false, true)));
        CombatSolver.Result r = CombatSolver.solve(in, CombatSolver.Config.d22());
        AssertJUnit.assertEquals(3, r.attacks.size());
        AssertJUnit.assertTrue(r.chosen.elim.contains(2));
        // elimNoSwing: the eliminated opponent does not swing back, so the kill line is feasible on its own
        AssertJUnit.assertTrue(r.chosen.feasible);
    }

    @Test
    public void cardImpactTable() throws Exception {
        String js = "{\"A\": {\"creature\": true, \"threat_shrunk\": 0.0}, \"B\": {\"creature\": true, \"threat_shrunk\": 0.1},"
                + " \"C\": {\"creature\": true, \"threat_shrunk\": 0.2}, \"D\": {\"creature\": false, \"threat_shrunk\": 9.0},"
                + " \"E\": {\"creature\": true, \"threat_shrunk\": NaN, \"decks\": {\"x\": 1}, \"l\": [1, 2]}}";
        Map<String, Double> m = CardImpact.fromJson(js);
        AssertJUnit.assertEquals(4, m.size());
        CardImpact imp = new CardImpact(new java.util.HashMap<>(Map.of("A", 0.0, "B", 0.1, "C", 0.2)), "t");
        AssertJUnit.assertEquals(0.1, imp.median(), 1e-12);
        AssertJUnit.assertEquals(0.18, imp.cut(), 1e-12);
        AssertJUnit.assertTrue(imp.isTop("C"));
        AssertJUnit.assertFalse(imp.isTop("B"));
        Map<String, Double> c = CardImpact.fromCsv("name,threat_shrunk,creature\n\"Big, Thing\",0.3,1\nLand,0.5,0\n");
        AssertJUnit.assertEquals(1, c.size());
        AssertJUnit.assertEquals(0.3, c.get("Big, Thing"), 1e-12);
    }
}
