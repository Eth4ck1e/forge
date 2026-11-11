package forge.ai.learned;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;

/** learned-ai D46: learned-play.properties parsing (-D wins), the upload manifest, places, and the outbox / sent moves. */
public class FriendsBuildTest {

    @AfterMethod
    public void clear() {
        for (String k : new String[] {"learned.uploadUrl", "learned.uploadToken", "learned.learnlog", "learned.gamesRequested", "learned.humanDeck", "learned.seatDecks"})
            System.clearProperty(k);
    }

    @Test
    public void configKeysAndPrecedence() {
        Properties file = new Properties();
        file.setProperty("seatProfiles", "2:Pressure");
        file.setProperty("seatDecks", "2:mirror-scd-token");
        file.setProperty("humanDeck", "mirror-40k-imperium");
        file.setProperty("gamesRequested", "10");
        file.setProperty("playerId", "alice");
        file.setProperty("config", "friends-a");
        file.setProperty("uploadUrl", "http://example.org/v1/live/upload");
        file.setProperty("uploadToken", "t0k");
        file.setProperty("learnlogDir", "live");
        file.setProperty("unknownKey", "x");
        Properties target = new Properties();
        target.setProperty("learned.seatProfiles", "2:Solver");   // given with -D: wins
        File base = new File("/opt/forge");
        Map<String, String> set = LearnedConfig.applyTo(file, target, base);
        AssertJUnit.assertEquals("2:Solver", target.getProperty("learned.seatProfiles"));
        AssertJUnit.assertFalse(set.containsKey("learned.seatProfiles"));
        AssertJUnit.assertEquals("2:mirror-scd-token", target.getProperty("learned.seatDecks"));
        AssertJUnit.assertEquals("mirror-40k-imperium", target.getProperty("learned.humanDeck"));
        AssertJUnit.assertEquals("10", target.getProperty("learned.gamesRequested"));
        AssertJUnit.assertEquals("alice", target.getProperty("learned.playerId"));
        AssertJUnit.assertEquals("friends-a", target.getProperty("learned.configId"));
        AssertJUnit.assertEquals("t0k", target.getProperty("learned.uploadToken"));
        AssertJUnit.assertEquals(new File(base, "live").getPath(), target.getProperty("learned.learnlog"));
        AssertJUnit.assertEquals(8, set.size());
        AssertJUnit.assertEquals("/abs/x", LearnedConfig.resolve("/abs/x", base).getPath());
        // humanDeck is slot 1's deck unless seatDecks names slot 1
        System.setProperty("learned.humanDeck", "mirror-40k-imperium");
        AssertJUnit.assertEquals("mirror-40k-imperium", SeatProfiles.deckSpecFor(1));
        System.setProperty("learned.seatDecks", "1:Token Triumph");
        AssertJUnit.assertEquals("Token Triumph", SeatProfiles.deckSpecFor(1));
    }

    private static List<LiveUpload.Seat> seats() {
        String[][] rows = {{"Mitch", "Forces of the Imperium", null, "loss"}, {"Pressure · Token Triumph [2]", "Token Triumph", "Pressure", "win"},
                {"Solver [3]", "Draconic Destruction", "Solver", "loss"}, {"Learned [4]", "Necron Dynasties", "Learned", "loss"}};
        List<LiveUpload.Seat> out = new ArrayList<>();
        for (String[] r : rows) {
            LiveUpload.Seat s = new LiveUpload.Seat();
            s.name = r[0]; s.deck = r[1]; s.profile = r[2]; s.result = r[3]; s.human = r[2] == null;
            out.add(s);
        }
        LiveUpload.places(out, Arrays.asList(30, 0, 22, 30));
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void manifestAndPlaces() {
        List<LiveUpload.Seat> s = seats();
        AssertJUnit.assertEquals(Integer.valueOf(2), s.get(0).place);   // tied last-turn losers share 2nd
        AssertJUnit.assertEquals(Integer.valueOf(1), s.get(1).place);
        AssertJUnit.assertEquals(Integer.valueOf(4), s.get(2).place);
        AssertJUnit.assertEquals(Integer.valueOf(2), s.get(3).place);
        String m = LiveUpload.manifest("b98cfd4cd4d", "friends-a", "alice", 1762841000000L, 1762841000000L, 1762842800000L,
                31, "AllOpponentsLost", s, "ab12", 4096);
        Map<String, Object> j = new Gson().fromJson(m, Map.class);
        AssertJUnit.assertEquals("b98cfd4cd4d", j.get("build"));
        AssertJUnit.assertEquals("alice", j.get("player"));
        AssertJUnit.assertEquals("2025-11-11T06:03:20.000Z", j.get("startedAt"));
        Map<String, Object> res = (Map<String, Object>) j.get("result");
        AssertJUnit.assertEquals(31.0, res.get("turns"));
        List<Map<String, Object>> seats = (List<Map<String, Object>>) res.get("seats");
        AssertJUnit.assertEquals(4, seats.size());
        AssertJUnit.assertEquals(Boolean.TRUE, seats.get(0).get("human"));
        AssertJUnit.assertNull(seats.get(0).get("profile"));
        AssertJUnit.assertEquals(4096.0, j.get("bytes"));
        AssertJUnit.assertEquals("ab12", j.get("sha256"));
    }

    @Test
    public void uploadMovesToSentOrKeepsInOutbox() throws Exception {
        AtomicInteger status = new AtomicInteger(500);
        AtomicReference<String> token = new AtomicReference<>(), manifest = new AtomicReference<>();
        AtomicInteger bytes = new AtomicInteger();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/live/upload", ex -> {
            token.set(ex.getRequestHeaders().getFirst("X-Live-Token"));
            manifest.set(new String(Base64.getDecoder().decode(ex.getRequestHeaders().getFirst("X-Live-Manifest")), StandardCharsets.UTF_8));
            bytes.set(ex.getRequestBody().readAllBytes().length);
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        srv.start();
        try {
            File dir = Files.createTempDirectory("friends").toFile();
            System.setProperty("learned.learnlog", dir.getPath());
            System.setProperty("learned.uploadUrl", "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/live/upload");
            System.setProperty("learned.uploadToken", "t0k");
            System.setProperty("learned.gamesRequested", "5");
            File gz = new File(dir, "live-20261110-220000-g1.jsonl.gz");
            Files.write(gz.toPath(), new byte[1234]);
            AssertJUnit.assertEquals("games played 1 / 5", LiveUpload.gamesLine());
            LiveUpload.gameClosed(gz, "{\"gameId\":1}");
            LiveUpload.retryOutboxAsync();   // drains on the worker; wait for it by queuing a marker
            waitWorker();
            AssertJUnit.assertTrue(new File(dir, "outbox/" + gz.getName()).isFile());   // 500: kept
            AssertJUnit.assertEquals("t0k", token.get());
            AssertJUnit.assertEquals("{\"gameId\":1}", manifest.get());
            AssertJUnit.assertEquals(1234, bytes.get());
            status.set(200);
            LiveUpload.retryOutboxAsync();
            waitWorker();
            AssertJUnit.assertTrue(new File(dir, "sent/" + gz.getName()).isFile());
            AssertJUnit.assertTrue(new File(dir, "sent/" + gz.getName() + ".manifest.json").isFile());
            AssertJUnit.assertFalse(new File(dir, "outbox/" + gz.getName()).exists());
            AssertJUnit.assertEquals("games played 1 / 5", LiveUpload.gamesLine());
            AssertJUnit.assertTrue(LiveUpload.noticeDue());
            AssertJUnit.assertFalse(LiveUpload.noticeDue());   // once per install
            AssertJUnit.assertTrue(LiveUpload.noticeText().contains("127.0.0.1") && LiveUpload.noticeText().contains("Games asked: 5"));
        } finally {
            srv.stop(0);
        }
    }

    /** the upload worker is single-threaded: a task queued after the drains runs after them */
    private static void waitWorker() throws Exception {
        LiveUpload.awaitIdle(10_000);
    }
}
