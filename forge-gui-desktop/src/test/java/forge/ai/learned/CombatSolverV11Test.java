package forge.ai.learned;

import static forge.ai.learned.CombatSolverRulesTest.board;
import static forge.ai.learned.CombatSolverRulesTest.cr;
import static forge.ai.learned.CombatSolverRulesTest.seat;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/** learned-ai D22 v1.1 (approved 2026-11-10): one board per rule where v1.1 differs from v1 as the rule says. */
public class CombatSolverV11Test {

    private static CombatSolver.Result v1(CombatSolver.Input in) { return CombatSolver.solve(in, CombatSolver.Config.d22()); }
    private static CombatSolver.Result v11(CombatSolver.Input in) { return CombatSolver.solve(in, CombatSolver.Config.d22v11()); }

    private static CombatSolver.Creature trample(CombatSolver.Creature c) { c.kf = 1 << 6; return c; }

    /** rule 1: heads-up, our clock (5 x 3/3 into one 4/4: 12 a turn, 21 life = 2 turns) beats theirs (4 a turn into
     * 37 = 10 turns): v1 keeps every 3/3 home (the 4/4 kills any of them), v1.1 races with everything. */
    @Test
    public void headsUpRaceWonAttacksWithEverything() {
        CombatSolver.Input in = board(seat(1, 37, cr(1, "Knight", 3, 3, false, false), cr(2, "Knight", 3, 3, false, false),
                        cr(3, "Knight", 3, 3, false, false), cr(4, "Knight", 3, 3, false, false), cr(5, "Knight", 3, 3, false, false)),
                seat(2, 21, cr(10, "Wall", 4, 4, false, false)));
        CombatSolver.Result a = v1(in), b = v11(in);
        AssertJUnit.assertTrue("v1 holds", a.attacks.isEmpty());
        AssertJUnit.assertEquals("no:trade", a.elig.get(1)[0]);
        AssertJUnit.assertEquals("v1.1", b.rules);
        AssertJUnit.assertEquals(2, b.raceUs);
        AssertJUnit.assertEquals(10, b.raceThem);
        AssertJUnit.assertTrue(b.raceWon);
        AssertJUnit.assertEquals("ok:race", b.elig.get(1)[0]);
        AssertJUnit.assertEquals("v1.1 attacks with all five", 5, b.attacks.size());
        AssertJUnit.assertTrue("the guard holds", b.chosen.feasible);
    }

    /** rule 1, guard side: the race is lost (we are at 9 facing 8 power a turn: their clock 2, ours 21/9 = 3), so
     * v1.1 holds exactly as v1 does. */
    @Test
    public void headsUpRaceLostHolds() {
        CombatSolver.Input in = board(seat(1, 9, cr(1, "Knight", 3, 3, false, false), cr(2, "Knight", 3, 3, false, false),
                        cr(3, "Knight", 3, 3, false, false), cr(4, "Knight", 3, 3, false, false)),
                seat(2, 21, cr(10, "Wall", 4, 4, false, false), cr(11, "Wall", 4, 4, false, true)));
        CombatSolver.Result a = v1(in), b = v11(in);
        AssertJUnit.assertFalse(b.raceWon);
        AssertJUnit.assertEquals(a.describe(), b.describe());
        AssertJUnit.assertTrue(b.attacks.isEmpty());
    }

    /** rule 2: four seats at full life, one 2/2; v1 pokes the open bystander for 2 (it gets through), v1.1 keeps it
     * home as a blocker; a lone 5/5 (meaningful damage) still attacks under v1.1. */
    @Test
    public void noLoneEarlyPoke() {
        CombatSolver.Input in = board(seat(1, 40, cr(1, "Bear", 2, 2, false, false)),
                seat(2, 40), seat(3, 40, cr(20, "Ogre", 3, 3, false, false)), seat(4, 40, cr(30, "Ogre", 3, 3, false, false)));
        in.oppStartLife = 120;
        CombatSolver.Result a = v1(in), b = v11(in);
        AssertJUnit.assertEquals("v1 pokes seat 2", "2", String.valueOf(a.attacks.get(1)));
        AssertJUnit.assertTrue(b.early);
        AssertJUnit.assertTrue("v1.1 keeps the bear home", b.attacks.isEmpty());
        AssertJUnit.assertTrue(b.droppedPoke >= 1);
        // not early any more (opponents at 60% or less of their starting life): the poke is back
        in.oppStartLife = 200;
        AssertJUnit.assertFalse(v11(in).early);
        AssertJUnit.assertEquals("2", String.valueOf(v11(in).attacks.get(1)));
        // meaningful damage: a lone 5/5 attacks
        in.oppStartLife = 120;
        in.seats.get(0).creatures.set(0, cr(1, "Giant", 5, 5, false, false));
        AssertJUnit.assertEquals(1, v11(in).attacks.size());
    }

    /** rule 3: our commander (key defender) leaves home under v1 to hit the open bystander; under v1.1 it stays
     * home, attacks the top threat when that is safe, and joins a kill on the bystander. */
    @Test
    public void keyDefenderHomeVsBystanderOutVsThreat() {
        CombatSolver.Input in = board(seat(1, 40, cr(1, "Our Commander", 5, 5, true, false)),
                seat(2, 40, cr(20, "Dragon", 6, 6, false, false), cr(21, "Dragon", 6, 6, false, false)), seat(3, 30));
        in.oppStartLife = 80;
        CombatSolver.Result a = v1(in), b = v11(in);
        AssertJUnit.assertEquals("top threat", 2, (int) a.threatRank.get(0));
        AssertJUnit.assertEquals("v1 sends the commander at the bystander", "3", String.valueOf(a.attacks.get(1)));
        AssertJUnit.assertTrue("v1.1 keeps it home", b.attacks.isEmpty());
        AssertJUnit.assertTrue(b.droppedKey >= 1);
        // the threat's dragons are tapped: the commander gets through to the top threat, and v1.1 sends it there
        CombatSolver.Input in2 = board(seat(1, 40, cr(1, "Our Commander", 5, 5, true, false)),
                seat(2, 40, cr(20, "Dragon", 6, 6, false, true), cr(21, "Dragon", 6, 6, false, true)), seat(3, 30));
        in2.oppStartLife = 80;
        AssertJUnit.assertEquals("2", String.valueOf(v11(in2).attacks.get(1)));
        // the bystander is at 5: a kill, so the key defender may go
        in.seats.get(2).life = 5;
        CombatSolver.Result k = v11(in);
        AssertJUnit.assertEquals("3", String.valueOf(k.attacks.get(1)));
        AssertJUnit.assertTrue(k.chosen.elim.contains(3));
    }

    /** rule 4: a 3/3 trampler (value 7) into the top threat's 3/3 (value 6) fails v1's trade test, so v1 hits the
     * open bystander; v1.1 takes the even trade into the top threat. */
    @Test
    public void evenTradeIntoTopThreat() {
        CombatSolver.Input in = board(seat(1, 40, trample(cr(1, "Rhino", 3, 3, false, false)), cr(2, "Wall", 0, 6, false, false)),
                seat(2, 20, cr(20, "Bear", 3, 3, false, false), cr(21, "Beast", 5, 5, false, true)), seat(3, 20));
        in.seats.get(0).creatures.get(1).canAttack = false;
        in.oppStartLife = 80;   // 40 of 80 left: not early
        CombatSolver.Result a = v1(in), b = v11(in);
        AssertJUnit.assertEquals("top threat", 2, (int) a.threatRank.get(0));
        AssertJUnit.assertEquals("no:trade", a.elig.get(1)[0]);
        AssertJUnit.assertEquals("v1 hits the bystander", "3", String.valueOf(a.attacks.get(1)));
        AssertJUnit.assertEquals("ok:threat-trade", b.elig.get(1)[0]);
        AssertJUnit.assertEquals("v1.1 attacks the top threat", "2", String.valueOf(b.attacks.get(1)));
        AssertJUnit.assertFalse(b.early);
    }

    /** v1 stays the default and reports itself */
    @Test
    public void defaultIsV1() {
        AssertJUnit.assertEquals(CombatSolver.RuleSet.V1, new CombatSolver.Config().rules);
        AssertJUnit.assertEquals(CombatSolver.RuleSet.V1, CombatSolver.RuleSet.parse(null));
        AssertJUnit.assertEquals(CombatSolver.RuleSet.V1, CombatSolver.RuleSet.parse("stock"));
        AssertJUnit.assertEquals(CombatSolver.RuleSet.V1_1, CombatSolver.RuleSet.parse(" V1.1 "));
        CombatSolver.Input in = board(seat(1, 40, cr(1, "Bear", 2, 2, false, false)), seat(2, 40));
        CombatSolver.Result r = v1(in);
        AssertJUnit.assertEquals("v1", r.rules);
        AssertJUnit.assertEquals(-1, r.raceUs);
    }
}
