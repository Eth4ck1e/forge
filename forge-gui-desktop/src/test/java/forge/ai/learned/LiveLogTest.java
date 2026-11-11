package forge.ai.learned;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;

/** learned-ai GUI play: -Dlearned.learnlog writes one file per game with the schema line and a game_result record. */
public class LiveLogTest extends AITest {

    @Test
    public void oneFilePerGameWithResult() throws Exception {
        initAndCreateGame();
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        d.setName("Token Triumph");
        for (String n : new String[] {"s1", "s2"}) players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi(n, null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        File dir = Files.createTempDirectory("livelog").toFile();
        AssertJUnit.assertFalse(LiveLog.wanted());
        LiveLog.beginGame(game);   // property absent: nothing
        AssertJUnit.assertEquals(0, dir.list().length);
        System.setProperty("learned.learnlog", dir.getAbsolutePath());
        try {
            LiveLog.beginGame(game);
            AssertJUnit.assertTrue(LearnLog.enabled());
            LiveLog.endGame(game);
            AssertJUnit.assertFalse(LearnLog.enabled());
        } finally {
            System.clearProperty("learned.learnlog");
        }
        File[] fs = dir.listFiles();
        AssertJUnit.assertEquals(1, fs.length);
        AssertJUnit.assertTrue(fs[0].getName(), fs[0].getName().matches("live-\\d{8}-\\d{6}-g\\d+\\.jsonl\\.gz"));
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(fs[0])), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) lines.add(l);
        }
        AssertJUnit.assertEquals(2, lines.size());
        AssertJUnit.assertTrue(lines.get(0).startsWith("{\"schema\":"));
        String res = lines.get(1);
        AssertJUnit.assertTrue(res, res.contains("\"hook\":\"game_result\"") && res.contains("\"live\":1")
                && res.contains("\"deck\":\"Token Triumph\"") && res.contains("\"human\":0"));
    }
}
