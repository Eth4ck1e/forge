package forge.ai.learned;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.testng.AssertJUnit;
import org.testng.SkipException;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.Gson;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * learned-ai D36: two Learned seats given two different ONNX files ({@code -model seat1:...} / {@code seat2:...}) in
 * one JVM each log their spell-choice decisions under their own model id; a stock seat logs "stock". Skipped when the
 * model repo is not checked out next to the fork.
 */
public class PerSeatModelTest extends AITest {
    private static final String MODELS = "../../forge-learned-ai/models/";

    @Test
    public void twoSeatsTwoModelsLogTheirOwnIds() throws Exception {
        File m1 = new File(MODELS + "spell_choice_h1cycle3.onnx");
        File m2 = new File(MODELS + "spell_choice_h1cycle4cal_deck_baked_forces_of_the_imperium.onnx");
        if (!m1.isFile() || !m2.isFile()) throw new SkipException("models not present: " + m1.getAbsolutePath());
        initAndCreateGame();   // loads the card database and the AI profiles

        LearnedHooks.clearScorers();
        LearnedHooks.resetProfileModels();
        OnnxScorer s1 = LearnedHooks.loadModel(m1.getPath()), s2 = LearnedHooks.loadModel(m2.getPath());
        AssertJUnit.assertNotSame(s1, s2);
        AssertJUnit.assertSame("models are cached by path", s1, LearnedHooks.loadModel(m1.getPath()));
        AssertJUnit.assertFalse(s1.id().equals(s2.id()));
        AssertJUnit.assertTrue(s1.id(), s1.id().startsWith("spell_choice_h1cycle3.onnx#"));
        LearnedHooks.setSeatScorer(1, LearnedHooks.SPELL_CHOICE, s1);
        LearnedHooks.setSeatScorer(2, LearnedHooks.SPELL_CHOICE, s2);

        File dir = java.nio.file.Files.createTempDirectory("perseat").toFile();
        LearnLog.open(dir, 4242L);
        try {
            List<RegisteredPlayer> players = Lists.newArrayList();
            Deck d = new Deck();
            String[] profiles = {"Learned", "Learned", "Default", "Default"};
            for (int i = 0; i < 4; i++) {
                LobbyPlayerAi lp = new LobbyPlayerAi("s" + (i + 1), null);
                lp.setAiProfile(profiles[i]);
                players.add(new RegisteredPlayer(d).setPlayer(lp));
            }
            GameRules rules = new GameRules(GameType.Constructed);
            Game game = new Game(players, rules, new Match(rules, players, "Test"));
            game.setAge(GameStage.Play);
            List<Player> seats = game.getRegisteredPlayers();
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seats.get(0));
            game.getPhaseHandler().onStackResolved();
            LearnLog.beginGame(1, 4242L);
            for (int i = 0; i < 3; i++) {
                Player p = seats.get(i);
                AssertJUnit.assertEquals(i + 1, LearnedHooks.seatNumber(p));
                addCards("Mountain", 2, p);
                Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
                List<SpellAbility> approved = new ArrayList<>();
                approved.add(bolt.getFirstSpellAbility());
                LearnedHooks.spellChoice(p, approved);
            }
            LearnLog.endGame();
        } finally {
            LearnLog.close();
            LearnedHooks.clearScorers();
            LearnedHooks.resetProfileModels();
        }

        Map<Integer, Map<String, Object>> bySeat = new HashMap<>();
        File f = new File(dir, "decisions-s4242.jsonl.gz");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(f)), StandardCharsets.UTF_8))) {
            Map<?, ?> head = new Gson().fromJson(r.readLine(), Map.class);
            AssertJUnit.assertEquals(3.0, ((Number) head.get("schema")).doubleValue());
            String line;
            while ((line = r.readLine()) != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rec = new Gson().fromJson(line, Map.class);
                if (!LearnedHooks.SPELL_CHOICE.equals(rec.get("hook"))) continue;
                bySeat.put(((Number) rec.get("seat")).intValue(), rec);
            }
        }
        AssertJUnit.assertEquals("one spell_choice record per seat: " + bySeat.keySet(), 3, bySeat.size());
        AssertJUnit.assertEquals(s1.id(), bySeat.get(1).get("model"));
        AssertJUnit.assertEquals("learned", bySeat.get(1).get("pilot"));
        AssertJUnit.assertEquals(s2.id(), bySeat.get(2).get("model"));
        AssertJUnit.assertEquals("learned", bySeat.get(2).get("pilot"));
        AssertJUnit.assertEquals("stock", bySeat.get(3).get("model"));
        AssertJUnit.assertEquals("stock", bySeat.get(3).get("pilot"));
        AssertJUnit.assertTrue("both models scored", s1.calls() > 0 && s2.calls() > 0);
    }
}
