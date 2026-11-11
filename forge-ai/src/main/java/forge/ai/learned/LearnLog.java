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

    private static boolean hooked;   // the close-on-exit hook is registered once per process

    /** Opens {@code dir/decisions-s<seed>.jsonl.gz} and writes the schema line. */
    public static synchronized void open(File dir, long seed0) throws IOException {
        dir.mkdirs();
        openFile(new File(dir, "decisions-s" + seed0 + ".jsonl.gz"), seed0);
    }

    /** Opens the given gzip JSON-lines file and writes the schema line (learned-ai GUI play: one file per live game). */
    public static synchronized void openFile(File f, long seed0) throws IOException {
        close();
        if (Features.size() != Features.names().size()) {
            throw new IllegalStateException("feature vector size " + Features.size() + " != names " + Features.names().size());
        }
        seed = seed0;
        out = new OutputStreamWriter(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16)), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("{\"schema\":").append(Features.SCHEMA).append(",\"seed\":").append(seed0);
        sb.append(",\"names\":"); strings(sb, Features.names());
        sb.append(",\"candNames\":{\"attack_target\":"); strings(sb, java.util.Arrays.asList(Features.CANDIDATE));
        sb.append(",\"spell_choice\":"); strings(sb, java.util.Arrays.asList(Features.SPELL_CANDIDATE));
        sb.append("},\"hooks\":[\"attack_target\",\"spell_choice\"]");
        sb.append(",\"spellLegacy\":").append(Features.SPELL_LEGACY);   // schema 5: spell_choice xa columns of schema 2-4
        sb.append(",\"raw\":[\"combat_snapshot\",\"combat_damage\",\"combat_result\",\"combat_attack\",\"turn_end\",\"mana_payment\",\"mana_lost\",\"seq_choice\",\"cast_util\"]}\n");
        out.write(sb.toString());
        if (!hooked) { Runtime.getRuntime().addShutdownHook(new Thread(LearnLog::close)); hooked = true; }
        forge.game.combat.Combat.learnedDamageObserver = CombatSnapshot::damage;   // D19: "combat_damage" records
        forge.game.combat.Combat.learnedEndObserver = CombatSnapshot::combatEnd;   // D19: "combat_result" records
        forge.game.phase.PhaseHandler.learnedCleanupObserver = ManaView::onCleanup;   // D33: "turn_end" records
        forge.game.phase.PhaseHandler.learnedManaLostObserver = ManaView::onManaLost;   // D33: "mana_lost" records
        forge.game.zone.MagicStack.learnedCastObserver = ManaView::onCast;   // D33: "mana_payment" records
    }

    public static void beginGame(int iGame, long gameSeed) {
        CombatSnapshot.resetPending();   // outside the LearnLog lock: CombatSnapshot locks itself, then LearnLog
        ManaView.resetGame();
        LearnedSequencing.resetGame();
        LearnedCastRules.resetGame();
        synchronized (LearnLog.class) { game = iGame; gseed = gameSeed; }
        if (enabled()) System.out.println("SIMNOTE learned schema " + Features.SCHEMA + ": spell_choice manaLeft = capacity (every mana source,"
                + " tapped or not, as in schema 2-4) - cost; manaUntapped / manaUntappedLeft (appended) count untapped usable sources + floating mana");
    }

    /** Flushes after each game so a killed worker loses at most the game in progress. */
    public static void endGame() {
        CombatSnapshot.flushPending("gameover");   // D19: the game ended during a combat (no end-of-combat step); lock order as above
        LearnedCastRules.flush();   // D44: cast_util records still waiting for the caster's next turn ("done":0)
        synchronized (LearnLog.class) {
            if (out == null) return;
            try { out.flush(); } catch (IOException ignored) {}
        }
    }

    public static synchronized void close() {
        forge.game.combat.Combat.learnedDamageObserver = null;
        forge.game.combat.Combat.learnedEndObserver = null;
        forge.game.phase.PhaseHandler.learnedCleanupObserver = null;
        forge.game.phase.PhaseHandler.learnedManaLostObserver = null;
        forge.game.zone.MagicStack.learnedCastObserver = null;
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) {}
        out = null;
    }

    public static long lines() { return lines; }

    /**
     * One decision. {@code seats} are the absolute seat numbers (1-based, as in SIMRESULT) in relative order;
     * {@code chosen} / {@code stock} are indices into {@code cands}, which are relative seat indices. {@code model}
     * (schema 3, D36) is the id of the model that piloted the seat for this hook, or "stock".
     */
    public static void decision(String hook, int actingSeat, int turn, int phase, int[] seats, int[] cands,
                                int chosen, int stock, String pilot, String model, float[] x, float[][] xa) {
        decision(hook, actingSeat, turn, phase, seats, cands, chosen, stock, pilot, model, x, xa, null);
    }

    /** As above; {@code extra} (schema 5) is appended to the record: further {@code ,"key":value} pairs, or null. */
    public static synchronized void decision(String hook, int actingSeat, int turn, int phase, int[] seats, int[] cands,
                                             int chosen, int stock, String pilot, String model, float[] x, float[][] xa, String extra) {
        if (out == null) return;
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"v\":").append(Features.SCHEMA).append(",\"hook\":\"").append(hook).append('"');
        sb.append(",\"seed\":").append(seed).append(",\"game\":").append(game).append(",\"gseed\":").append(gseed).append(",\"seat\":").append(actingSeat);
        sb.append(",\"turn\":").append(turn).append(",\"phase\":").append(phase);
        sb.append(",\"seats\":"); ints(sb, seats);
        sb.append(",\"cands\":"); ints(sb, cands);
        sb.append(",\"chosen\":").append(chosen).append(",\"stock\":").append(stock).append(",\"pilot\":\"").append(pilot).append('"');
        sb.append(",\"model\":\"").append(model == null ? "stock" : model.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');   // D36 (schema 3)
        sb.append(",\"x\":"); floats(sb, x);
        sb.append(",\"xa\":[");
        for (int i = 0; i < xa.length; i++) { if (i > 0) sb.append(','); floats(sb, xa[i]); }
        sb.append(']');
        if (extra != null) sb.append(extra);
        sb.append("}\n");
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
