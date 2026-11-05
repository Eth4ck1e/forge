package forge.ai.learned;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * learned-ai M1 decision log: one gzip JSON-lines file per sim process, one line per logged decision, plus a schema
 * line at the top. Decisions carry the seed and game index of the game they belong to, which joins them to the
 * {@code SIMRESULT} line of the same game in the worker's stdout log (M1.2 label integrity).
 * <p>
 * Off unless {@link #open(File, long)} was called (the sim's {@code -learnlog <dir>} flag); when off every call is a
 * null check. Writes are synchronised; a decision is a few KB and there are ~50 per game, so no background thread is
 * needed (M1.4 measures the overhead).
 */
public final class LearnLog {
    private static Writer out;
    private static long seed;
    private static int game;
    private static long gseed;   // D20: the game's own seed (SIMRESULT gameSeed); -s <gseed> -n 1 replays it
    private static long lines;

    private LearnLog() {}

    public static boolean enabled() { return out != null; }

    /** Opens {@code dir/decisions-s<seed>.jsonl.gz} and writes the schema line. */
    public static synchronized void open(File dir, long seed0) throws IOException {
        close();
        if (Features.size() != Features.names().size()) {
            throw new IllegalStateException("feature vector size " + Features.size() + " != names " + Features.names().size());
        }
        dir.mkdirs();
        seed = seed0;
        File f = new File(dir, "decisions-s" + seed0 + ".jsonl.gz");
        out = new OutputStreamWriter(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16)), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("{\"schema\":").append(Features.SCHEMA).append(",\"seed\":").append(seed0);
        sb.append(",\"names\":"); strings(sb, Features.names());
        sb.append(",\"candNames\":{\"attack_target\":"); strings(sb, java.util.Arrays.asList(Features.CANDIDATE));
        sb.append(",\"spell_choice\":"); strings(sb, java.util.Arrays.asList(Features.SPELL_CANDIDATE));
        sb.append("},\"hooks\":[\"attack_target\",\"spell_choice\"]}\n");
        out.write(sb.toString());
        Runtime.getRuntime().addShutdownHook(new Thread(LearnLog::close));
    }

    public static synchronized void beginGame(int iGame, long gameSeed) { game = iGame; gseed = gameSeed; }

    /** Flushes after each game so a killed worker loses at most the game in progress. */
    public static synchronized void endGame() {
        if (out == null) return;
        try { out.flush(); } catch (IOException ignored) {}
    }

    public static synchronized void close() {
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) {}
        out = null;
    }

    public static long lines() { return lines; }

    /**
     * One decision. {@code seats} are the absolute seat numbers (1-based, as in SIMRESULT) in relative order;
     * {@code chosen} / {@code stock} are indices into {@code cands}, which are relative seat indices.
     */
    public static synchronized void decision(String hook, int actingSeat, int turn, int phase, int[] seats, int[] cands,
                                             int chosen, int stock, String pilot, float[] x, float[][] xa) {
        if (out == null) return;
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"v\":").append(Features.SCHEMA).append(",\"hook\":\"").append(hook).append('"');
        sb.append(",\"seed\":").append(seed).append(",\"game\":").append(game).append(",\"gseed\":").append(gseed).append(",\"seat\":").append(actingSeat);
        sb.append(",\"turn\":").append(turn).append(",\"phase\":").append(phase);
        sb.append(",\"seats\":"); ints(sb, seats);
        sb.append(",\"cands\":"); ints(sb, cands);
        sb.append(",\"chosen\":").append(chosen).append(",\"stock\":").append(stock).append(",\"pilot\":\"").append(pilot).append('"');
        sb.append(",\"x\":"); floats(sb, x);
        sb.append(",\"xa\":[");
        for (int i = 0; i < xa.length; i++) { if (i > 0) sb.append(','); floats(sb, xa[i]); }
        sb.append("]}\n");
        try { out.write(sb.toString()); lines++; } catch (IOException e) { System.err.println("learnlog: " + e); }
    }

    /** A record of another shape (e.g. a combat snapshot): {@code body} is the inside of the JSON object. */
    public static synchronized void raw(String hook, String body) {
        if (out == null) return;
        StringBuilder sb = new StringBuilder(body.length() + 96);
        sb.append("{\"v\":").append(Features.SCHEMA).append(",\"hook\":\"").append(hook).append("\",\"seed\":").append(seed).append(",\"game\":").append(game).append(",\"gseed\":").append(gseed).append(',').append(body).append("}\n");
        try { out.write(sb.toString()); lines++; } catch (IOException e) { System.err.println("learnlog: " + e); }
    }

    private static void ints(StringBuilder sb, int[] a) {
        sb.append('[');
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        sb.append(']');
    }

    private static void floats(StringBuilder sb, float[] a) {
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            float v = a[i];
            if (v == (int) v) sb.append((int) v); else sb.append(v);
        }
        sb.append(']');
    }

    private static void strings(StringBuilder sb, List<String> a) {
        sb.append('[');
        for (int i = 0; i < a.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(a.get(i)).append('"'); }
        sb.append(']');
    }
}
