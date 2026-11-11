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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.Gson;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * learned-ai D44: each casting-window rule set picks a different window than the baseline (stock) on a scripted
 * board, and a cast writes a cast_util record with its fields. Seat 1 is a Learned seat with stock spell choice.
 */
public class CastRulesTest extends AITest {

    private Game game;
    private Player me;
    private List<Player> opps;

    private void build() {
        initAndCreateGame();
        LearnedHooks.setScorer(LearnedHooks.SPELL_CHOICE, LearnedHooks.NONE);
        LearnedCastRules.clearOverrides();
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        String[] profiles = {"Learned", "Default", "Default", "Default"};
        for (int i = 0; i < 4; i++) {
            LobbyPlayerAi lp = new LobbyPlayerAi("s" + (i + 1), null);
            lp.setAiProfile(profiles[i]);
            players.add(new RegisteredPlayer(d).setPlayer(lp));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        List<Player> ps = game.getRegisteredPlayers();
        me = ps.get(0);
        opps = new ArrayList<>(ps.subList(1, 4));
        LearnedCastRules.resetGame();
    }

    @AfterMethod
    public void clear() {
        LearnedCastRules.clearOverrides();
        LearnedHooks.clearScorers();
    }

    private void at(PhaseType step, Player active) {
        game.getPhaseHandler().setCombat(null);
        game.getPhaseHandler().devModeSet(step, active);
        game.getPhaseHandler().onStackResolved();
    }

    private void attack(Player who, Card... cards) {
        Combat combat = new Combat(who);
        for (Card c : cards) combat.addAttacker(c, me);
        game.getPhaseHandler().setCombat(combat);
    }

    private String stockPick() {
        List<SpellAbility> l = ((PlayerControllerAi) me.getController()).getAi().chooseSpellAbilityToPlay();
        return l == null || l.isEmpty() ? null : l.get(0).getHostCard().getName();
    }

    private SpellAbility act(String r) {
        LearnedCastRules.setOverride(r);
        return LearnedCastRules.act(me, LearnedCastRules.rules(me));
    }

    /** a creature stock removal wants dead (stock leaves a Grizzly Bears alone) */
    private Card bear(Player p) { Card c = addCard("Serra Angel", p); c.setSickness(false); return c; }

    @Test
    public void removalCombatWaitsForAnAttackerAtUs() {
        build();
        addCards("Swamp", 2, me);
        addCardToZone("Doom Blade", me, ZoneType.Hand);
        Card bear = bear(opps.get(0));
        at(PhaseType.MAIN1, me);
        LearnedCastRules.setOverride("baseline");
        AssertJUnit.assertEquals("baseline: stock kills at its first main phase", "Doom Blade", stockPick());
        LearnedCastRules.setOverride("removal-combat");
        AssertJUnit.assertNull("removal-combat: not in our main phase", stockPick());
        AssertJUnit.assertNull(act("removal-combat"));
        at(PhaseType.COMBAT_DECLARE_ATTACKERS, opps.get(0));
        attack(opps.get(0), bear);
        SpellAbility sa = act("removal-combat");
        AssertJUnit.assertNotNull("removal-combat: cast on the attacker", sa);
        AssertJUnit.assertEquals("Doom Blade", sa.getHostCard().getName());
        AssertJUnit.assertTrue(sa.getTargets().getTargetCards().contains(bear));
    }

    @Test
    public void sorceryRemovalOnTheTopThreat() {
        build();
        addCards("Mountain", 2, me);
        addCardToZone("Flame Slash", me, ZoneType.Hand);
        bear(opps.get(0));
        Card angel = addCard("Serra Angel", opps.get(1)); angel.setSickness(false);
        addCard("Serra Angel", opps.get(1)).setSickness(false);
        addCards("Plains", 5, opps.get(1));
        at(PhaseType.MAIN1, me);
        Player top = (Player) LearnedHooks.observing(me, true, () -> LearnedCastRules.threats(me)).get(0)[0];
        AssertJUnit.assertSame(opps.get(1), top);
        SpellAbility sa = act("removal-combat");
        AssertJUnit.assertNotNull("sorcery removal in our main phase", sa);
        AssertJUnit.assertEquals("Flame Slash", sa.getHostCard().getName());
        AssertJUnit.assertSame("aimed at the top threat", top, sa.getTargets().getFirstTargetedCard().getController());
        // not at instant speed on someone else's turn, and stock may not cast it on its own
        sa.resetTargets();
        LearnedCastRules.setOverride("removal-combat");
        AssertJUnit.assertNull(stockPick());
    }

    @Test
    public void removalPrecombatKillsBeforeTheSwing() {
        build();
        addCards("Swamp", 2, me);
        addCardToZone("Doom Blade", me, ZoneType.Hand);
        me.setLife(5, null);   // lowest life at the table: predicted target (no attack history)
        Card bear = bear(opps.get(0));
        at(PhaseType.COMBAT_BEGIN, opps.get(0));
        game.getPhaseHandler().setCombat(new Combat(opps.get(0)));
        AssertJUnit.assertNull("removal-combat waits for declared attackers", act("removal-combat"));
        SpellAbility sa = act("removal-precombat");
        AssertJUnit.assertNotNull("removal-precombat: beginning of combat", sa);
        AssertJUnit.assertTrue(sa.getTargets().getTargetCards().contains(bear));
        sa.resetTargets();
        // once attackers are declared the precombat window has passed
        at(PhaseType.COMBAT_DECLARE_ATTACKERS, opps.get(0));
        attack(opps.get(0), bear);
        AssertJUnit.assertNull(act("removal-precombat"));
        LearnedCastRules.setOverride("removal-precombat");
        AssertJUnit.assertFalse("Doom Blade".equals(stockPick()));
    }

    @Test
    public void holdInstantsUntilTheEndStepBeforeOurTurn() {
        build();
        addCards("Island", 2, me);
        addCardToZone("Think Twice", me, ZoneType.Hand);
        for (int k = 0; k < 10; k++) addCardToZone("Island", me, ZoneType.Library);
        at(PhaseType.MAIN2, me);
        LearnedCastRules.setOverride("baseline");
        AssertJUnit.assertEquals("baseline casts it in our main 2", "Think Twice", stockPick());
        LearnedCastRules.setOverride("hold-instants");
        AssertJUnit.assertNull("hold-instants: nothing on our own turn", stockPick());
        AssertJUnit.assertNull(act("hold-instants"));
        at(PhaseType.END_OF_TURN, opps.get(0));   // seat 2's end step: stock casts here, not the latest window
        LearnedCastRules.setOverride("baseline");
        AssertJUnit.assertEquals("Think Twice", stockPick());
        LearnedCastRules.setOverride("hold-instants");
        AssertJUnit.assertNull(stockPick());
        AssertJUnit.assertNull(act("hold-instants"));
        at(PhaseType.END_OF_TURN, opps.get(2));   // seat 4: our turn is next
        SpellAbility sa = act("hold-instants");
        AssertJUnit.assertNotNull("hold-instants: cast at the end step before our turn", sa);
        AssertJUnit.assertEquals("Think Twice", sa.getHostCard().getName());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void castUtilRecord() throws Exception {
        build();
        File dir = java.nio.file.Files.createTempDirectory("castutil").toFile();
        LearnLog.open(dir, 99L);
        try {
            LearnLog.beginGame(1, 99L);
            addCards("Swamp", 2, me);
            addCardToZone("Doom Blade", me, ZoneType.Hand);
            Card bear = bear(opps.get(0));
            at(PhaseType.COMBAT_DECLARE_ATTACKERS, opps.get(0));
            attack(opps.get(0), bear);
            SpellAbility sa = act("removal-combat");
            AssertJUnit.assertNotNull(sa);
            List<SpellAbility> res = new ArrayList<>();
            res.add(sa);
            LearnedCastRules.noteDecision(me, LearnedCastRules.RCOMBAT, res, true);
            LearnedCastRules.onCast(sa);
            AssertJUnit.assertEquals(1, LearnedCastRules.pendingCount());
            LearnLog.endGame();
        } finally {
            LearnLog.close();
        }
        Map<String, Object> rec = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(new File(dir, "decisions-s99.jsonl.gz"))), StandardCharsets.UTF_8))) {
            Map<?, ?> head = new Gson().fromJson(r.readLine(), Map.class);
            AssertJUnit.assertTrue(((List<?>) head.get("raw")).contains("cast_util"));
            String line;
            while ((line = r.readLine()) != null) {
                Map<String, Object> m = new Gson().fromJson(line, Map.class);
                if ("cast_util".equals(m.get("hook"))) rec = m;
            }
        }
        AssertJUnit.assertNotNull(rec);
        AssertJUnit.assertEquals((double) Features.SCHEMA, rec.get("v"));
        AssertJUnit.assertEquals("removal-combat", rec.get("rules"));
        AssertJUnit.assertEquals("removal-instant", rec.get("cls"));
        AssertJUnit.assertEquals("planner", rec.get("pilot"));
        AssertJUnit.assertEquals("combat:attacker", rec.get("why"));
        AssertJUnit.assertEquals("COMBAT_DECLARE_ATTACKERS", rec.get("step"));
        AssertJUnit.assertEquals(0.0, rec.get("myTurn"));
        for (String k : new String[] {"seat", "turn", "phase", "active", "nextIsMe", "combat", "stack", "attackedN", "removal", "plan",
                "card", "cardId", "api", "cmc", "manaOpen", "life", "threat", "top", "targets", "out"}) AssertJUnit.assertTrue(k, rec.containsKey(k));
        List<Map<String, Object>> t = (List<Map<String, Object>>) rec.get("targets");
        AssertJUnit.assertEquals(1, t.size());
        AssertJUnit.assertEquals("attacker", t.get(0).get("role"));
        AssertJUnit.assertEquals(4.0, t.get(0).get("pow"));
        Map<String, Object> out = (Map<String, Object>) rec.get("out");
        for (String k : new String[] {"turn", "done", "idleMana", "lifeMe", "lifeTop", "topAlive", "tgt", "attackStopped", "traded"}) AssertJUnit.assertTrue(k, out.containsKey(k));
        AssertJUnit.assertEquals(0.0, out.get("done"));
        AssertJUnit.assertNotNull(out.get("attackStopped"));
    }

}
