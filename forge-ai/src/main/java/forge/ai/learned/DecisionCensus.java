package forge.ai.learned;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * learned-ai M0.4: counts AI decisions by category and, for each category, how many times the state evaluator and
 * the game simulator were consulted while that decision was being made. Off unless {@link #enabled} is set (the
 * sim's {@code -census} flag); when off every hook is a single volatile read.
 * <p>
 * The current category is kept per thread and inherited by threads started while a decision is in progress (the AI
 * evaluation threads), so evaluator calls made on those threads are attributed to the decision that spawned them.
 * Counters are process-wide; {@link #snapshotJson(boolean)} reads them and optionally resets, once per game.
 */
public final class DecisionCensus {
    public static volatile boolean enabled = false;

    private static final InheritableThreadLocal<ArrayDeque<String>> CTX = new InheritableThreadLocal<ArrayDeque<String>>() {
        @Override protected ArrayDeque<String> initialValue() { return new ArrayDeque<>(); }
        @Override protected ArrayDeque<String> childValue(ArrayDeque<String> parent) { return new ArrayDeque<>(parent); }
    };
    private static final ConcurrentHashMap<String, LongAdder> DECISIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> EVALUATOR = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> SIMULATOR = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> EXTRA = new ConcurrentHashMap<>();

    private DecisionCensus() {}

    private static void bump(ConcurrentHashMap<String, LongAdder> m, String key) {
        m.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /** Marks the start of a decision of the given category on the current thread. Pair with {@link #exit()}. */
    public static void enter(String category) {
        if (!enabled) return;
        CTX.get().push(category);
        bump(DECISIONS, category);
    }

    public static void exit() {
        if (!enabled) return;
        ArrayDeque<String> d = CTX.get();
        if (!d.isEmpty()) d.pop();
    }

    private static String current() {
        ArrayDeque<String> d = CTX.get();
        return d.isEmpty() ? "none" : d.peek();
    }

    /** One call of {@code GameStateEvaluator.getScoreForGameState}, attributed to the decision in progress. */
    public static void evaluatorCall() {
        if (!enabled) return;
        bump(EVALUATOR, current());
    }

    /** One call of {@code GameSimulator.simulateSpellAbility}, attributed to the decision in progress. */
    public static void simulatorCall() {
        if (!enabled) return;
        bump(SIMULATOR, current());
    }

    /** Any other event worth counting (e.g. which rule picked the attack target). */
    public static void note(String key) {
        if (!enabled) return;
        bump(EXTRA, key);
    }

    private static Map<String, Long> drain(ConcurrentHashMap<String, LongAdder> m, boolean reset) {
        Map<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : m.entrySet()) {
            out.put(e.getKey(), reset ? e.getValue().sumThenReset() : e.getValue().sum());
        }
        return out;
    }

    private static void append(StringBuilder sb, String name, Map<String, Long> m) {
        sb.append('"').append(name).append("\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (e.getValue() == 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "'")).append("\":").append(e.getValue());
        }
        sb.append('}');
    }

    /** JSON object with decisions, evaluator calls and simulator calls per category, plus extra notes. */
    public static String snapshotJson(boolean reset) {
        StringBuilder sb = new StringBuilder("{");
        append(sb, "decisions", drain(DECISIONS, reset)); sb.append(',');
        append(sb, "evaluator", drain(EVALUATOR, reset)); sb.append(',');
        append(sb, "simulator", drain(SIMULATOR, reset)); sb.append(',');
        append(sb, "notes", drain(EXTRA, reset));
        return sb.append('}').toString();
    }
}
