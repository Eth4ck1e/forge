package forge.ai.learned;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * learned-ai D46 (friends' builds): upload of closed live game files. With {@code learned.uploadUrl} set, a closed
 * file goes to {@code <learnlogDir>/outbox/} with its manifest ({@code <file>.manifest.json}), then a single background
 * daemon thread POSTs it (body = the gz file, headers X-Live-Token and X-Live-Manifest = base64 manifest JSON, 30 s
 * timeouts). HTTP 200 moves it to {@code sent/}; anything else leaves it in the outbox, retried at the next launch
 * and after each later game. Never runs on the UI thread. Stdlib only.
 */
public final class LiveUpload {
    private LiveUpload() {}

    public static final int TIMEOUT_MS = 30_000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "learned-live-upload");
        t.setDaemon(true);
        return t;
    });

    public static String url() { String u = System.getProperty("learned.uploadUrl"); return u == null || u.trim().isEmpty() ? null : u.trim(); }
    public static File dir() {
        String d = System.getProperty("learned.learnlog");
        return d == null || d.trim().isEmpty() ? null : LearnedConfig.resolve(d.trim(), null);
    }
    public static File outbox() { File d = dir(); return d == null ? null : new File(d, "outbox"); }
    public static File sent() { File d = dir(); return d == null ? null : new File(d, "sent"); }

    /** one seat of the manifest result */
    public static final class Seat {
        public String name, deck, profile, result;
        public boolean human;
        public Integer place;   // null when unknown (game not over)
    }

    /** the manifest JSON (see class doc); sha256 hex and bytes of the gz body */
    public static String manifest(String build, String config, String player, long gameId, long startedAt, long endedAt,
                                  int turns, String endReason, List<Seat> seats, String sha256, long bytes) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"build\":"); ManaView.str(sb, build);
        sb.append(",\"config\":"); nstr(sb, config);
        sb.append(",\"player\":"); nstr(sb, player);
        sb.append(",\"gameId\":").append(gameId);
        sb.append(",\"startedAt\":"); ManaView.str(sb, iso(startedAt));
        sb.append(",\"endedAt\":"); ManaView.str(sb, iso(endedAt));
        sb.append(",\"result\":{\"turns\":").append(turns).append(",\"endReason\":"); nstr(sb, endReason);
        sb.append(",\"seats\":[");
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":"); nstr(sb, s.name);
            sb.append(",\"deck\":"); nstr(sb, s.deck);
            sb.append(",\"profile\":"); nstr(sb, s.profile);
            sb.append(",\"human\":").append(s.human);
            sb.append(",\"result\":"); nstr(sb, s.result);
            sb.append(",\"place\":").append(s.place == null ? "null" : s.place.toString()).append('}');
        }
        sb.append("]},\"sha256\":"); ManaView.str(sb, sha256);
        sb.append(",\"bytes\":").append(bytes).append('}');
        return sb.toString();
    }

    private static void nstr(StringBuilder sb, String s) { if (s == null) sb.append("null"); else ManaView.str(sb, s); }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** places from results and loss turns: winners (and draws) 1, then losers by later loss turn first; ties share */
    public static void places(List<Seat> seats, List<Integer> lossTurns) {
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            if (s.result == null || "unknown".equals(s.result)) { s.place = null; continue; }
            if (!"loss".equals(s.result)) { s.place = 1; continue; }
            int better = 0;
            for (int j = 0; j < seats.size(); j++) {
                if (j == i) continue;
                Seat o = seats.get(j);
                if (o.result == null || "unknown".equals(o.result)) continue;
                if (!"loss".equals(o.result) || lossTurns.get(j) > lossTurns.get(i)) better++;
            }
            s.place = better + 1;
        }
    }

    public static String sha256(File f) throws IOException {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** a live file closed: queue it in the outbox and upload in the background (no-op without uploadUrl) */
    public static void gameClosed(File gz, String manifestJson) {
        if (url() == null || gz == null) return;
        try {
            File ob = outbox();
            ob.mkdirs();
            File dst = new File(ob, gz.getName());
            Files.move(gz.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.write(new File(ob, gz.getName() + ".manifest.json").toPath(), manifestJson.getBytes(StandardCharsets.UTF_8));
            System.out.println("SIMNOTE learned upload queued " + dst.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            System.out.println("SIMNOTE learned upload could not queue " + gz + ": " + e);
            return;
        }
        retryOutboxAsync();
    }

    /** uploads every outbox file in the background (launch, after each game) */
    public static void retryOutboxAsync() {
        if (url() == null || outbox() == null) return;
        WORKER.submit(LiveUpload::drainOutbox);
    }

    static void drainOutbox() {
        File ob = outbox();
        File[] fs = ob == null ? null : ob.listFiles((d, n) -> n.endsWith(".jsonl.gz"));
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            File mf = new File(ob, f.getName() + ".manifest.json");
            try {
                String manifest = mf.isFile() ? new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8)
                        : "{\"build\":\"" + LearnedConfig.build() + "\",\"gameId\":null,\"sha256\":\"" + sha256(f) + "\",\"bytes\":" + f.length() + "}";
                int code = post(f, manifest);
                if (code == 200) {
                    File s = sent();
                    s.mkdirs();
                    Files.move(f.toPath(), new File(s, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    if (mf.isFile()) Files.move(mf.toPath(), new File(s, mf.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("SIMNOTE learned upload ok " + f.getName() + " -> sent/");
                } else {
                    System.out.println("SIMNOTE learned upload failed " + f.getName() + " http " + code + " (kept in outbox/, retried next launch / next game)");
                }
            } catch (IOException | RuntimeException e) {
                System.out.println("SIMNOTE learned upload failed " + f.getName() + " " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (kept in outbox/)");
            }
        }
    }

    /** POST the file; returns the HTTP status */
    static int post(File f, String manifestJson) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url()).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/gzip");
            String tok = System.getProperty("learned.uploadToken");
            if (tok != null) c.setRequestProperty("X-Live-Token", tok.trim());
            c.setRequestProperty("X-Live-Manifest", Base64.getEncoder().encodeToString(manifestJson.getBytes(StandardCharsets.UTF_8)));
            c.setFixedLengthStreamingMode(f.length());
            try (OutputStream out = c.getOutputStream()) { Files.copy(f.toPath(), out); }
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    /** live games recorded so far: live-*.jsonl.gz in the learnlog dir, sent/ and outbox/ */
    public static int gamesPlayed() {
        File d = dir();
        if (d == null) return 0;
        int n = 0;
        for (File x : new File[] {d, sent(), outbox()}) {
            String[] fs = x.list((dd, nm) -> nm.startsWith("live-") && nm.endsWith(".jsonl.gz"));
            if (fs != null) n += fs.length;
        }
        return n;
    }

    /** "games played k / N" (N = learned.gamesRequested, "?" when unset) */
    public static String gamesLine() {
        String req = System.getProperty("learned.gamesRequested");
        return "games played " + gamesPlayed() + " / " + (req == null || req.trim().isEmpty() ? "?" : req.trim());
    }

    /** the upload host for the notice */
    public static String host() {
        try { return new URL(url()).getHost(); } catch (Exception e) { return String.valueOf(url()); }
    }

    /** first-launch notice: true once per install (marker file in the learnlog dir), then false */
    public static boolean noticeDue() {
        File d = dir();
        if (url() == null || d == null) return false;
        File m = new File(d, ".upload-notice-shown");
        if (m.exists()) return false;
        try { d.mkdirs(); Files.write(m.toPath(), iso(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) {}
        return true;
    }

    public static String noticeText() {
        String req = System.getProperty("learned.gamesRequested"), deck = System.getProperty("learned.humanDeck");
        return "This build records your games (your hand is never recorded) and uploads them to " + host() + "."
                + " Games asked: " + (req == null ? "?" : req.trim()) + (deck == null ? "" : " as " + deck.trim()) + ".";
    }

    /** waits until every queued upload task has run (tests) */
    public static void awaitIdle(long timeoutMs) throws Exception {
        WORKER.submit(() -> { }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static List<String> listOutbox() {
        List<String> out = new ArrayList<>();
        File ob = outbox();
        String[] fs = ob == null ? null : ob.list();
        if (fs != null) out.addAll(Arrays.asList(fs));
        return out;
    }
}
