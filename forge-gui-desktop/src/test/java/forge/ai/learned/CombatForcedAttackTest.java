package forge.ai.learned;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.Gson;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

/**
 * learned-ai issue #28: the solver seat's attack declaration honours what the engine requires: a goaded creature and a
 * creature that "attacks each combat if able" are fixed in every solver line (also when the stock declaration left
 * them out), a player-level requirement (Seeker of Slaanesh: "each opponent must attack with at least one creature")
 * is met, and a per-defender attack cost (Ghostly Prison) bans that defender. The full tests run the real declare
 * attackers step and check that the engine accepts the solver's line (status applied, no fallbackReason).
 */
public class CombatForcedAttackTest extends AITest {

    private Game game;
    private List<Player> seats;

    private void newGame() {
        initAndCreateGame();   // card database and AI profiles
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        String[] profiles = {"Learned", "Default", "Default"};
        for (int i = 0; i < 3; i++) {
            LobbyPlayerAi lp = new LobbyPlayerAi("s" + (i + 1), null);
            lp.setAiProfile(profiles[i]);
            players.add(new RegisteredPlayer(d).setPlayer(lp));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        seats = game.getRegisteredPlayers();
        for (Player p : seats) for (int i = 0; i < 10; i++) addCardToZone("Plains", p, ZoneType.Library);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seats.get(0));
        game.getPhaseHandler().onStackResolved();
    }

    private Card ready(String name, Player p) { Card c = addCard(name, p); c.setSickness(false); return c; }

    /** two big untapped blockers per opponent: every small attack is a bad trade (the D22_V1 rule keeps it home) */
    private void walls() {
        for (int i = 1; i < 3; i++) { ready("Colossal Dreadmaw", seats.get(i)); ready("Colossal Dreadmaw", seats.get(i)); }
    }

    /** runs the real declare-attackers step with the seat on the solver; returns the seat's combat_attack records */
    private List<Map<String, Object>> declare() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("forced").toFile();
        LearnedCombat.setOverride(LearnedCombat.SOLVER);
        LearnLog.open(dir, 28L);
        try {
            LearnLog.beginGame(1, 28L);
            game.getAction().checkStateEffects(true);
            game.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_DECLARE_ATTACKERS);
            LearnLog.endGame();
        } finally {
            LearnLog.close();
            LearnedCombat.setOverride(null);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(new File(dir, "decisions-s28.jsonl.gz"))), StandardCharsets.UTF_8))) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rec = new Gson().fromJson(line, Map.class);
                if ("combat_attack".equals(rec.get("hook"))) out.add(rec);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> chosenLine(Map<String, Object> rec) {
        List<Object> cands = (List<Object>) rec.get("cands");
        return (Map<String, Object>) cands.get(((Number) rec.get("chosen")).intValue());
    }

    private void assertAccepted(Map<String, Object> rec) {
        AssertJUnit.assertEquals(rec.toString(), "applied", rec.get("status"));
        AssertJUnit.assertNull(rec.toString(), rec.get("fallbackReason"));
        AssertJUnit.assertTrue(rec.toString(), ((Number) rec.get("chosen")).intValue() < ((Number) rec.get("stock")).intValue());
        AssertJUnit.assertTrue("the engine accepts the declaration", CombatUtil.validateAttackers(game.getCombat()));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void goadedAndMustAttackCreaturesAreInEverySolverLine() throws Exception {
        newGame();
        walls();
        Player me = seats.get(0);
        Card bears = ready("Grizzly Bears", me);
        bears.addGoad(game.getNextTimestamp(), seats.get(1));
        Card jugg = ready("Juggernaut", me);
        ready("Savannah Lions", me);   // optional: should stay home against the walls

        // board level, as if the stock had declared nothing: both are fixed from the engine's requirements
        Combat empty = new Combat(me);
        game.getPhaseHandler().setCombat(empty);
        game.getAction().checkStateEffects(true);
        CombatBoard b = CombatBoard.build(me, empty);
        AssertJUnit.assertTrue(b.input.fixed.toString(), b.input.fixed.containsKey(bears.getId()) && b.input.fixed.containsKey(jugg.getId()));
        AssertJUnit.assertEquals("goad: not the goading player", 3, b.input.fixed.get(bears.getId()).seat);
        CombatSolver.Result res = CombatSolver.solve(b.input, LearnedCombat.config());
        for (CombatSolver.Line l : res.top)
            AssertJUnit.assertTrue(l.attacks.toString(), l.attacks.containsKey(bears.getId()) && l.attacks.containsKey(jugg.getId()));
        game.getPhaseHandler().setCombat(null);

        // the real step
        List<Map<String, Object>> recs = declare();
        AssertJUnit.assertEquals(1, recs.size());
        Map<String, Object> rec = recs.get(0);
        assertAccepted(rec);
        Map<String, Object> att = (Map<String, Object>) chosenLine(rec).get("att");
        AssertJUnit.assertTrue(att.toString(), att.containsKey(String.valueOf(bears.getId())) && att.containsKey(String.valueOf(jugg.getId())));
        Combat c = game.getCombat();
        AssertJUnit.assertTrue(c.isAttacking(bears) && c.isAttacking(jugg));
        GameEntity d = c.getDefenderByAttacker(bears);
        AssertJUnit.assertNotSame("goaded creature attacks someone else", seats.get(1), d);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void playerLevelRequirementIsMet() throws Exception {
        newGame();
        walls();
        ready("Seeker of Slaanesh", seats.get(1));   // each opponent must attack with at least one creature if able
        Player me = seats.get(0);
        Card bears = ready("Grizzly Bears", me);
        ready("Savannah Lions", me);

        Combat empty = new Combat(me);
        game.getPhaseHandler().setCombat(empty);
        game.getAction().checkStateEffects(true);
        CombatBoard b = CombatBoard.build(me, empty);
        AssertJUnit.assertFalse("Seeker gives a player-level requirement", b.input.mustAttackAny.isEmpty());
        CombatSolver.Result res = CombatSolver.solve(b.input, LearnedCombat.config());
        AssertJUnit.assertTrue(res.reqState.toString(), res.reqState.contains(1));
        for (CombatSolver.Line l : res.top) AssertJUnit.assertEquals("exactly one attacker meets it: " + l.attacks, 1, l.attacks.size());
        game.getPhaseHandler().setCombat(null);

        List<Map<String, Object>> recs = declare();
        AssertJUnit.assertEquals(1, recs.size());
        Map<String, Object> rec = recs.get(0);
        assertAccepted(rec);
        AssertJUnit.assertEquals(1, ((Map<String, Object>) chosenLine(rec).get("att")).size());
        AssertJUnit.assertEquals(1, game.getCombat().getAttackers().size());
        AssertJUnit.assertTrue(bears.getId() > 0);
    }

    @Test
    public void attackCostBansThatDefender() throws Exception {
        newGame();
        ready("Ghostly Prison", seats.get(1));   // attacking seat 2 costs {2} per creature
        Player me = seats.get(0);
        Card giant = ready("Hill Giant", me);
        Combat empty = new Combat(me);
        game.getPhaseHandler().setCombat(empty);
        game.getAction().checkStateEffects(true);
        CombatBoard b = CombatBoard.build(me, empty);
        AssertJUnit.assertTrue(b.input.noAttack.toString(), b.input.noAttack.containsKey(giant.getId())
                && b.input.noAttack.get(giant.getId()).contains(CombatSolver.Target.player(2)));
        CombatSolver.Result res = CombatSolver.solve(b.input, LearnedCombat.config());
        for (CombatSolver.Line l : res.top) {
            CombatSolver.Target t = l.attacks.get(giant.getId());
            AssertJUnit.assertTrue(l.attacks.toString(), t == null || t.seat != 2);
        }
        game.getPhaseHandler().setCombat(null);
    }
}
