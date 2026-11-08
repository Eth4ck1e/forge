package forge.ai.learned;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.ai.AiController;
import forge.ai.AiProps;
import forge.ai.PlayerControllerAi;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * learned-ai F3 path (b): hooks at the stock AI's decision points (D16). Each hook sees the live game, the
 * candidates and the stock AI's own choice; it logs the decision when {@link LearnLog} is open and, when the acting
 * player's AI profile has the learned hook switched on and a model is loaded, returns the model's choice instead.
 * <p>
 * H2 attack target: {@code AiAttackController.choosePreferredDefenderPlayerImpl}. The stock rule is consulted from
 * ~30 places (ability logic asks "who is my preferred opponent" too) and from combat prediction; only the call made
 * while the AI declares its attackers is a real decision, and only that one is logged. {@link #beginDeclare} /
 * {@link #endDeclare} mark it on the declaring thread.
 */
public final class LearnedHooks {
    public static final String ATTACK_TARGET = "attack_target";
    public static final String SPELL_CHOICE = "spell_choice";
    /** D22 phase 2: the combat solver's attack declaration (combat_attack records; fork candidates = top-N lines + stock) */
    public static final String ATTACK_ASSIGN = "attack_assign";
    /** How many heuristic-approved plays the spell-choice hook collects per priority (plus "pass"). */
    public static int spellCandidates = 4;

    /** Scores (state, candidate) pairs; null until a model is loaded (M3). */
    public interface Scorer {
        float[] score(float[] x, float[][] xa);
        /** D36: the id written into each decision record's {@code model} field (ONNX: file name + short hash). */
        default String id() { return "custom"; }
    }

    /** D36: a scorer with a fixed id (e.g. the {@code random} diagnostic). */
    public static Scorer named(final String id, final Scorer s) {
        return new Scorer() {
            @Override public float[] score(float[] x, float[][] xa) { return s.score(x, xa); }
            @Override public String id() { return id; }
        };
    }

    /** Unprefixed {@code -model hook=path}: every Learned seat (hook -> scorer). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Scorer> SCORERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** D36: {@code -model seatN:hook=path}: seat N only (1-based, SIMRESULT order); overrides the unprefixed table. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.Map<String, Scorer>> SEAT_SCORERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** D36: profile LEARNED_MODEL scorers per seat (seat -> hook -> scorer), filled at seat creation, cleared per game. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.Map<String, Scorer>> PROFILE_SCORERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** D36: every ONNX model loaded in this JVM, by canonical path (CLI and profile share it); one session per file. */
    private static final java.util.Map<String, OnnxScorer> MODEL_CACHE = new java.util.LinkedHashMap<>();
    /**
     * Exploration for data generation (M4 off-policy fix): with this probability a stock-piloted seat attacks a
     * uniformly random candidate instead of the stock rule's pick, and the record is logged with pilot "explore" and
     * the propensity 1/n. Zero in evaluation runs.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Double> EXPLORE = new java.util.concurrent.ConcurrentHashMap<>();
    public static void setExploreRate(String hook, double rate) { EXPLORE.put(hook, rate); }
    public static double exploreRate(String hook) { return EXPLORE.getOrDefault(hook, 0.0); }
    /** Conservative override: the learned pick replaces the stock pick only when its score beats the stock pick's by this much. */
    public static volatile float margin = 0.0f;
    private static final ThreadLocal<Player> DECLARING = new ThreadLocal<>();

    private LearnedHooks() {}

    // ---- D20 counterfactual forks -------------------------------------------------------------------------------
    // The k-th counted decision of (seat, hook) is the k-th record with that seat and hook in the game's decision
    // file (0-based): counting happens exactly where a spell_choice / attack_target record would be written, whether
    // or not the learn log is open. Counting consumes no randomness. A fork (-fork) forces one candidate at one
    // address and optionally reseeds MyRandom right after the forced decision. Game 1 of the JVM only (run -n 1).
    private static final java.util.HashMap<String, int[]> COUNTS = new java.util.HashMap<>();
    private static volatile boolean forkConfigured = false;
    private static boolean forkDone = false;
    private static int forkSeat, forkIndex, forkChoose;
    private static String forkHook;
    private static long forkSeed;

    /** Parses the {@code -fork} tokens: seat=<1-based> hook=<spell_choice|attack_target|attack_assign> index=<k> choose=<pos> seed=<s>. */
    public static synchronized void configureFork(List<String> tokens) {
        Integer seat = null, index = null, choose = null; String hook = null; long seed = 0;
        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("fork: expected key=value, got " + t);
            String k = t.substring(0, eq).trim(), v = t.substring(eq + 1).trim();
            switch (k) {
                case "seat": seat = Integer.parseInt(v); break;
                case "hook": hook = v; break;
                case "index": index = Integer.parseInt(v); break;
                case "choose": choose = Integer.parseInt(v); break;
                case "seed": seed = Long.parseLong(v); break;
                default: throw new IllegalArgumentException("fork: unknown key " + k);
            }
        }
        if (seat == null || hook == null || index == null || choose == null)
            throw new IllegalArgumentException("fork: seat, hook, index and choose are required");
        if (!SPELL_CHOICE.equals(hook) && !ATTACK_TARGET.equals(hook) && !ATTACK_ASSIGN.equals(hook))
            throw new IllegalArgumentException("fork: hook must be spell_choice, attack_target or attack_assign, got " + hook);
        forkSeat = seat; forkHook = hook; forkIndex = index; forkChoose = choose; forkSeed = seed;
        forkDone = false; forkConfigured = true;
        System.out.println("SIMNOTE fork armed seat=" + seat + " hook=" + hook + " index=" + index + " choose=" + choose + " seed=" + seed);
    }

    /** Counts this decision and returns the forced candidate position if it is the fork point, else -1. */
    static synchronized int countDecision(String hook, int seat, int nCands, int turn) {
        int[] c = COUNTS.computeIfAbsent(hook + "#" + seat, k -> new int[1]);
        int idx = c[0]++;
        if (!forkConfigured || forkDone || seat != forkSeat || idx != forkIndex || !hook.equals(forkHook)) return -1;
        forkDone = true;
        if (forkChoose < 0 || forkChoose >= nCands) {
            System.out.println("SIMNOTE fork invalid choose=" + forkChoose + " (record has " + nCands + " candidates); not applied");
            return -1;
        }
        System.out.println("SIMNOTE fork applied seat=" + seat + " hook=" + hook + " index=" + idx + " choose=" + forkChoose + " turn=" + turn);
        return forkChoose;
    }

    /** Whether the next counted decision of (seat, hook) is the armed fork point (consumes nothing). */
    static synchronized boolean forkPointNext(String hook, int seat) {
        if (!forkConfigured || forkDone || seat != forkSeat || !hook.equals(forkHook)) return false;
        int[] c = COUNTS.get(hook + "#" + seat);
        return (c == null ? 0 : c[0]) == forkIndex;
    }

    /** Called after the forced decision is logged: reseeds the game's random source with the branch seed (0 = keep). */
    static void forkReseed() {
        if (forkSeed == 0) return;
        if (SNAPSHOT_DEPTH.get()[0] > 0) { SNAPSHOT_DEPTH.get()[1] = 1; return; }   // inside a saved state: reseed after its restore
        forge.util.MyRandom.setRandom(new java.util.Random(forkSeed));
        System.out.println("SIMNOTE fork reseed " + forkSeed);
    }

    /** End of a game: reports an unreached fork, disarms it (game 1 only) and resets the decision counters. */
    public static synchronized void forkGameEnd() {
        if (forkConfigured && !forkDone) {
            int[] c = COUNTS.get(forkHook + "#" + forkSeat);
            System.out.println("SIMNOTE fork not reached (seat " + forkSeat + " hook " + forkHook + " saw " + (c == null ? 0 : c[0]) + " decisions)");
        }
        forkConfigured = false;
        COUNTS.clear();
    }

    /**
     * {@code -model [seatN:]hook=none} (or bare {@code none}): the seat plays exactly stock for that hook while its
     * learned logging stays on (records carry model "stock"); stops the lookup, so the profile LEARNED_MODEL is not loaded.
     */
    public static final Scorer NONE = named("none", (x, xa) -> { throw new IllegalStateException("NONE scorer"); });

    public static void setScorer(String hook, Scorer s) { if (s == null) SCORERS.remove(hook); else SCORERS.put(hook, s); }
    public static Scorer scorer(String hook) { Scorer s = SCORERS.get(hook); return s == NONE ? null : s; }
    /** D36: seat-specific scorer ({@code -model seatN:hook=...}); null removes it. */
    public static void setSeatScorer(int seat, String hook, Scorer s) {
        java.util.Map<String, Scorer> m = SEAT_SCORERS.computeIfAbsent(seat, k -> new java.util.concurrent.ConcurrentHashMap<>());
        if (s == null) m.remove(hook); else m.put(hook, s);
    }
    /** D36: drops every CLI and profile scorer (tests; the model cache stays). */
    public static synchronized void clearScorers() {
        SCORERS.clear(); SEAT_SCORERS.clear(); PROFILE_SCORERS.clear();
    }

    /** D36: loads (or reuses) the ONNX model at {@code path}; one session per canonical path for the whole JVM. */
    public static synchronized OnnxScorer loadModel(String path) throws Exception {
        String key = canonical(new java.io.File(path));
        OnnxScorer s = MODEL_CACHE.get(key);
        if (s == null) {
            s = new OnnxScorer(key);
            MODEL_CACHE.put(key, s);
        }
        return s;
    }

    /** D36: the loaded ONNX models (SIMMODEL reports one entry per model). */
    public static synchronized List<OnnxScorer> loadedModels() { return new ArrayList<>(MODEL_CACHE.values()); }

    /**
     * D36: the scorer seat {@code seat} uses for {@code hook}: {@code -model seatN:} first, then unprefixed
     * {@code -model}, then the seat's own profile LEARNED_MODEL. Null when none (the seat plays stock).
     */
    public static Scorer scorerFor(int seat, String hook) {
        Scorer s = lookup(seat, hook);
        return s == NONE ? null : s;
    }

    /** {@link #scorerFor} without resolving {@link #NONE} (an explicit "no model" still blocks the profile fallback). */
    private static Scorer lookup(int seat, String hook) {
        java.util.Map<String, Scorer> m = SEAT_SCORERS.get(seat);
        Scorer s = m == null ? null : m.get(hook);
        if (s != null) return s;
        s = SCORERS.get(hook);
        if (s != null) return s;
        m = PROFILE_SCORERS.get(seat);
        return m == null ? null : m.get(hook);
    }

    /** D36: the {@code model} field of a decision record: the id of the model that piloted it, else "stock". */
    private static String modelId(boolean learned, Scorer s) { return learned && s != null ? s.id() : "stock"; }

    public static void beginDeclare(Player ai) { DECLARING.set(ai); }
    public static void endDeclare() { DECLARING.remove(); }

    private static boolean hookOn(Player ai, AiProps prop) {
        if (!(ai.getController() instanceof PlayerControllerAi)) return false;
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        if (!aic.getBoolProperty(AiProps.LEARNED_HOOKS) || !aic.getBoolProperty(prop)) return false;
        ensureModelsLoaded(aic, seatNumber(ai), false);
        return true;
    }

    /**
     * Eager profile-model load: called when a seat is created for a game (LobbyPlayerAi.createIngamePlayer, after the
     * profile is applied), so the seat's first hooked decision already has its scorer. Reads only the profile.
     */
    public static void prepareSeat(Player ai) {
        if (!(ai.getController() instanceof PlayerControllerAi)) return;
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        // the seat is created before it is registered with the game: its seat number is the next registration slot
        int seat = ai.getGame().getRegisteredPlayers().indexOf(ai) + 1;
        if (seat <= 0) seat = ai.getGame().getRegisteredPlayers().size() + 1;
        if (!aic.getBoolProperty(AiProps.LEARNED_HOOKS)) {
            java.util.Map<String, Scorer> m = SEAT_SCORERS.get(seat);
            if (m != null && !m.isEmpty())
                System.out.println("SIMNOTE learned seat=" + seat + " has -model " + m.keySet() + " but its profile has LEARNED_HOOKS off; seat plays stock");
            return;
        }
        ensureModelsLoaded(aic, seat, true);
        LearnedCombat.announce(aic, seat);   // D22 phase 2: SIMNOTE combat mode of this Learned seat, per game
    }

    /** D36: seats whose profile models were looked up this game (the lookup happens once per seat per game). */
    private static final java.util.Set<Integer> PROFILE_TRIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * D20: puts the profile-model state back to how a fresh process starts a game (nothing loaded), so game k of a
     * multi-game run plays like a standalone {@code -n 1} run of its seed. The scorers are put back when the game's
     * seats are created ({@link #prepareSeat}), before any decision; the ONNX sessions stay cached by path.
     */
    public static synchronized void resetProfileModels() {
        PROFILE_SCORERS.clear();
        PROFILE_TRIED.clear();
    }

    /**
     * GUI and any run without {@code -model}: load the scorers named by the profile's LEARNED_MODEL
     * ("spell_choice=learned/spell_choice.onnx;attack_target=..."), paths relative to the AI profile folder
     * (res/ai) unless absolute. Done when a Learned seat is created ({@link #prepareSeat}), with the hooked decisions as a fallback.
     */
    private static void ensureModelsLoaded(AiController aic, int seat, boolean announce) {
        if (seat <= 0 || PROFILE_TRIED.contains(seat)) return;
        synchronized (LearnedHooks.class) {
            if (!PROFILE_TRIED.add(seat)) return;
            java.util.Map<String, Scorer> mine = new java.util.concurrent.ConcurrentHashMap<>();
            String spec = aic.getProperty(AiProps.LEARNED_MODEL);
            if (spec != null) for (String part : spec.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String hook = part.substring(0, eq).trim(); String path = part.substring(eq + 1).trim();
                if (lookup(seat, hook) != null) continue;   // -model on the command line wins (seat-prefixed or not; none too)
                java.io.File f = new java.io.File(path);
                if (!f.isAbsolute() && forge.ai.AiProfileUtil.getProfileDir() != null) f = new java.io.File(forge.ai.AiProfileUtil.getProfileDir(), path);
                try {
                    boolean fresh = !MODEL_CACHE.containsKey(canonical(f));
                    OnnxScorer sc = loadModel(f.getPath());
                    if (fresh) System.out.println("learned-ai: loaded " + hook + " model " + sc.path() + " (" + sc.id() + ")");
                    mine.put(hook, sc);
                } catch (Exception e) {
                    System.err.println("learned-ai: could not load " + hook + " model " + f.getPath() + ": " + e);
                }
            }
            PROFILE_SCORERS.put(seat, mine);
            if (announce) {
                // D36: one line per seat per hooked model at game start, naming where the model came from
                java.util.List<String> stockHooks = new java.util.ArrayList<>();
                for (String hook : new String[] {SPELL_CHOICE, ATTACK_TARGET}) {
                    Scorer sc = lookup(seat, hook);
                    if (sc == null || sc == NONE) { stockHooks.add(hook); }
                    if (sc == null) continue;
                    java.util.Map<String, Scorer> sm = SEAT_SCORERS.get(seat);
                    String src = sm != null && sm.get(hook) == sc ? "seat" : SCORERS.get(hook) == sc ? "global" : "profile";
                    AiProps prop = SPELL_CHOICE.equals(hook) ? AiProps.LEARNED_SPELL_CHOICE : AiProps.LEARNED_ATTACK_TARGET;
                    System.out.println("SIMNOTE learned seat=" + seat + " hook=" + hook + " model=" + sc.id() + " src=" + src
                            + (sc == NONE ? " (stock with logging)" : aic.getBoolProperty(prop) ? "" : " (hook off in profile; seat plays stock)"));
                }
                if (stockHooks.size() == 2)
                    System.out.println("SIMNOTE learned seat=" + seat + " stock-with-logging (no model for " + stockHooks + "; plays as stock, records pilot=stock)");
            }
        }
    }

    private static String canonical(java.io.File f) {
        try { return f.getCanonicalPath(); } catch (java.io.IOException e) { return f.getAbsolutePath(); }
    }

    /**
     * Attack-target hook. Returns the learned choice, or null to keep the stock choice.
     * @param threat the stock rule's threat score per opponent (a feature)
     * @param stock the opponent the stock rule picked
     */
    public static Player attackTarget(Player ai, List<Player> opponents, Map<Player, Integer> threat, Player stock) {
        boolean declaring = DECLARING.get() == ai;
        boolean on = hookOn(ai, AiProps.LEARNED_ATTACK_TARGET);   // loads profile models if not yet loaded
        Scorer scorer = scorerFor(seatNumber(ai), ATTACK_TARGET);   // D36: per seat
        boolean learned = on && scorer != null;
        final double er = exploreRate(ATTACK_TARGET);
        boolean explore = declaring && !learned && er > 0 && forge.util.MyRandom.getRandom().nextDouble() < er;
        if (!declaring || opponents.size() < 2) return null;
        final int forced = countDecision(ATTACK_TARGET, seatNumber(ai), opponents.size(), ai.getGame().getPhaseHandler().getTurn());
        if (!LearnLog.enabled() && !learned && !explore && forced < 0) return null;

        List<Player> seats = Features.seatsFrom(ai);
        int[] cands = new int[opponents.size()];
        int stockIdx = -1;
        for (int i = 0; i < opponents.size(); i++) {
            Player o = opponents.get(i);
            cands[i] = seats.indexOf(o);
            if (o == stock) stockIdx = i;
        }
        // observation only unless a model acts on them: the features are taken from a saved state that is put back
        final Object[] feats = observing(ai.getGame(), !learned, () -> {
            float[][] fa = new float[opponents.size()][];
            for (int i = 0; i < opponents.size(); i++)
                fa[i] = Features.attackCandidate(ai, opponents.get(i), seats, threat.getOrDefault(opponents.get(i), 0), opponents.get(i) == stock);
            return new Object[] {Features.state(ai), fa};
        });
        float[] x = (float[]) feats[0];
        float[][] xa = (float[][]) feats[1];
        int chosen = stockIdx;
        if (learned) {
            float[] s = scorer.score(x, xa);
            int best = -1;
            for (int i = 0; i < s.length; i++) if (best < 0 || s[i] > s[best]) best = i;
            if (best >= 0 && (stockIdx < 0 || s[best] - s[stockIdx] > margin)) chosen = best;
        } else if (explore) {
            chosen = forge.util.MyRandom.getRandom().nextInt(opponents.size());
        }
        if (forced >= 0) chosen = forced;   // after the normal path, so the forced-same branch draws the same randomness
        if (LearnLog.enabled()) {
            int[] abs = new int[seats.size()];
            for (int i = 0; i < seats.size(); i++) abs[i] = seatNumber(seats.get(i));
            PhaseType ph = ai.getGame().getPhaseHandler().getPhase();
            LearnLog.decision(ATTACK_TARGET, seatNumber(ai), ai.getGame().getPhaseHandler().getTurn(), ph == null ? 0 : ph.ordinal(),
                    abs, cands, chosen, stockIdx, forced >= 0 ? "fork" : learned ? "learned" : (explore ? "explore" : "stock"), modelId(learned, scorer), x, xa);
        }
        if (forced >= 0) forkReseed();
        return (learned || explore || forced >= 0) && chosen >= 0 ? opponents.get(chosen) : null;
    }

    /** Whether the spell-choice hook needs the list of approved plays (logging, exploration or a loaded model). */
    public static boolean spellChoiceActive(Player ai) {
        return LearnLog.enabled() || forkConfigured || exploreRate(SPELL_CHOICE) > 0 || (hookOn(ai, AiProps.LEARNED_SPELL_CHOICE) && scorerFor(seatNumber(ai), SPELL_CHOICE) != null);
    }

    /**
     * Whether the seat's spell_choice play is stock, so the candidate list is observation only: no model acts for the
     * seat (stock profiles, {@code -model none}, Learned seats without a spell_choice model) and no exploration. Such a
     * seat's collection and record are taken from a saved state ({@link #observedSpellChoice}) and it then plays the
     * plain stock pick, so logging, fork counting and fork runs leave its play exactly as without them.
     */
    public static boolean observesSpellChoice(Player ai) {
        if (exploreRate(SPELL_CHOICE) > 0) return false;
        return !(hookOn(ai, AiProps.LEARNED_SPELL_CHOICE) && scorerFor(seatNumber(ai), SPELL_CHOICE) != null);
    }

    /**
     * Spell choice of a seat that plays stock ({@link #observesSpellChoice}): collects the approved plays into a copy
     * of {@code saList} from a saved state, logs/counts them, puts the state back and returns the stock pick. At the
     * armed fork point the forced decision acts: the collection is redone on the live state (exactly the acting path
     * of older jars) and the forced candidate is played, then the branch reseed applies.
     */
    public static forge.game.spellability.SpellAbility observedSpellChoice(Player ai, List<forge.game.spellability.SpellAbility> saList,
            java.util.function.Function<List<forge.game.spellability.SpellAbility>, List<forge.game.spellability.SpellAbility>> collect,
            java.util.function.Supplier<forge.game.spellability.SpellAbility> stock) {
        if (forkPointNext(SPELL_CHOICE, seatNumber(ai))) {
            boolean any = observing(ai.getGame(), true, () -> !collect.apply(new ArrayList<>(saList)).isEmpty());
            if (!any) return stock.get();   // no record here: the fork point is a later decision
            return spellChoice(ai, collect.apply(saList));
        }
        observing(ai.getGame(), true, () -> spellChoice(ai, collect.apply(new ArrayList<>(saList))));   // the collection sorts its list in place
        return stock.get();
    }

    /** Runs a learn-log record ({@code work}) from a saved state that is put back afterwards: logging never changes play. */
    public static <T> T transparently(Player ai, String hook, java.util.function.Supplier<T> work) {
        return observing(ai.getGame(), LearnLog.enabled(), work);
    }

    /** per thread: [depth of nested saved states, fork reseed pending (1)] */
    private static final ThreadLocal<int[]> SNAPSHOT_DEPTH = ThreadLocal.withInitial(() -> new int[2]);

    /**
     * Runs {@code work}; when {@code observe}, the draw state (randomness, id counters, timestamp/card ids, AI card
     * memories, AiCache, AiController fields) is saved before and put back after, so the work leaves play unchanged.
     * Nested calls share the outermost saved state. A fork reseed requested inside is applied after the restore.
     */
    static <T> T observing(forge.game.Game game, boolean observe, java.util.function.Supplier<T> work) {
        final int[] st = SNAPSHOT_DEPTH.get();
        if (!observe || st[0] > 0) return work.get();
        final Object[] snap = saveDrawState(game);
        st[0]++;
        try {
            return work.get();
        } finally {
            st[0]--;
            restoreDrawState(game, snap);
            if (st[1] != 0) { st[1] = 0; forkReseed(); }
        }
    }

    private static final String[][] STATIC_COUNTERS = {
            {"forge.game.Game", "maxId"}, {"forge.game.spellability.SpellAbility", "maxId"},
            {"forge.game.spellability.SpellAbilityStackInstance", "maxId"}, {"forge.game.trigger.Trigger", "maxId"},
            {"forge.game.replacement.ReplacementEffect", "maxId"}, {"forge.game.staticability.StaticAbility", "maxId"},
            {"forge.game.combat.CombatView", "nextId"},
    };

    private static java.lang.reflect.Field field(String cls, String name) throws Exception {
        java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** [rng copy (null if not copyable), static id counters, game timestamp, cardIdCounter, hiddenCardIdCounter, AI card memories, AiCache]. */
    private static Object[] saveDrawState(forge.game.Game game) {
        Object rng = null;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream oo = new java.io.ObjectOutputStream(bo)) { oo.writeObject(forge.util.MyRandom.getRandom()); }
            rng = bo.toByteArray();
        } catch (Exception e) {
            System.err.println("learned-ai: cannot snapshot the random source (" + e + "); logged play may drift");
        }
        int[] counters = new int[STATIC_COUNTERS.length];
        int cardId = 0, hiddenId = 0;
        try {
            for (int k = 0; k < counters.length; k++) counters[k] = field(STATIC_COUNTERS[k][0], STATIC_COUNTERS[k][1]).getInt(null);
            cardId = field("forge.game.Game", "cardIdCounter").getInt(game);
            hiddenId = field("forge.game.Game", "hiddenCardIdCounter").getInt(game);
        } catch (Exception e) {
            throw new IllegalStateException("learned-ai: id counters not found: " + e, e);
        }
        return new Object[] {rng, counters, game.getTimestamp(), cardId, hiddenId, saveMemories(game), saveAiCache(), saveControllers(game)};
    }

    /** Mutable AiController fields (shallow): the predicted combat and other per-priority caches evaluation fills. */
    private static final java.util.List<java.lang.reflect.Field> CONTROLLER_FIELDS = new ArrayList<>();
    static {
        for (java.lang.reflect.Field f : AiController.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(m) || java.lang.reflect.Modifier.isFinal(m)) continue;
            f.setAccessible(true);
            CONTROLLER_FIELDS.add(f);
        }
    }

    private static java.util.Map<AiController, Object[]> saveControllers(forge.game.Game game) {
        java.util.Map<AiController, Object[]> all = new java.util.IdentityHashMap<>();
        for (Player p : game.getRegisteredPlayers()) {
            if (!(p.getController() instanceof PlayerControllerAi)) continue;
            AiController aic = ((PlayerControllerAi) p.getController()).getAi();
            Object[] v = new Object[CONTROLLER_FIELDS.size()];
            try {
                for (int k = 0; k < v.length; k++) v[k] = CONTROLLER_FIELDS.get(k).get(aic);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("learned-ai: AiController fields: " + e, e);
            }
            all.put(aic, v);
        }
        return all;
    }

    private static void restoreControllers(java.util.Map<AiController, Object[]> all) {
        try {
            for (java.util.Map.Entry<AiController, Object[]> e : all.entrySet())
                for (int k = 0; k < e.getValue().length; k++) CONTROLLER_FIELDS.get(k).set(e.getKey(), e.getValue()[k]);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("learned-ai: AiController fields: " + ex, ex);
        }
    }

    /** AiCache (global: board evaluations, life-in-danger) fills while the AI evaluates; a hit skips the random draws. */
    @SuppressWarnings("unchecked")
    private static com.google.common.collect.Multimap<Object, Object> aiCache() {
        try {
            return (com.google.common.collect.Multimap<Object, Object>) field("forge.ai.AiCache", "dataMap").get(null);
        } catch (Exception e) {
            throw new IllegalStateException("learned-ai: AiCache.dataMap not found: " + e, e);
        }
    }

    private static com.google.common.collect.Multimap<Object, Object> saveAiCache() {
        com.google.common.collect.Multimap<Object, Object> live = aiCache();
        synchronized (live) { return com.google.common.collect.ArrayListMultimap.create(live); }
    }

    private static void restoreAiCache(com.google.common.collect.Multimap<Object, Object> was) {
        com.google.common.collect.Multimap<Object, Object> live = aiCache();
        synchronized (live) { live.clear(); live.putAll(was); }
    }

    /** The AI card memory of every AI seat (AI checks remember cards while they evaluate a play). */
    @SuppressWarnings("unchecked")
    private static java.util.Map<Object, java.util.Map<Object, java.util.Set<Object>>> saveMemories(forge.game.Game game) {
        java.util.Map<Object, java.util.Map<Object, java.util.Set<Object>>> all = new java.util.IdentityHashMap<>();
        for (Player p : game.getRegisteredPlayers()) {
            if (!(p.getController() instanceof PlayerControllerAi)) continue;
            java.util.Map<Object, java.util.Set<Object>> live = memoryMap(((PlayerControllerAi) p.getController()).getAi().getCardMemory());
            java.util.Map<Object, java.util.Set<Object>> copy = new java.util.HashMap<>();
            for (java.util.Map.Entry<Object, java.util.Set<Object>> e : live.entrySet()) copy.put(e.getKey(), new java.util.HashSet<>(e.getValue()));
            all.put(live, copy);
        }
        return all;
    }

    private static void restoreMemories(java.util.Map<Object, java.util.Map<Object, java.util.Set<Object>>> all) {
        for (java.util.Map.Entry<Object, java.util.Map<Object, java.util.Set<Object>>> e : all.entrySet()) {
            @SuppressWarnings("unchecked")
            java.util.Map<Object, java.util.Set<Object>> live = (java.util.Map<Object, java.util.Set<Object>>) e.getKey();
            for (java.util.Map.Entry<Object, java.util.Set<Object>> m : live.entrySet()) {
                java.util.Set<Object> was = e.getValue().get(m.getKey());
                m.getValue().retainAll(was == null ? java.util.Collections.emptySet() : was);
                if (was != null) m.getValue().addAll(was);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Object, java.util.Set<Object>> memoryMap(forge.ai.AiCardMemory mem) {
        try {
            return (java.util.Map<Object, java.util.Set<Object>>) ((com.google.common.base.Supplier<?>) field("forge.ai.AiCardMemory", "memoryMap").get(mem)).get();
        } catch (Exception e) {
            throw new IllegalStateException("learned-ai: AiCardMemory.memoryMap not found: " + e, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreDrawState(forge.game.Game game, Object[] snap) {
        if (snap[0] != null) {
            try (java.io.ObjectInputStream oi = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream((byte[]) snap[0]))) {
                forge.util.MyRandom.setRandom((java.util.Random) oi.readObject());
            } catch (Exception e) {
                throw new IllegalStateException("learned-ai: cannot restore the random source: " + e, e);
            }
        }
        try {
            int[] counters = (int[]) snap[1];
            for (int k = 0; k < counters.length; k++) field(STATIC_COUNTERS[k][0], STATIC_COUNTERS[k][1]).setInt(null, counters[k]);
            game.dangerouslySetTimestamp((Long) snap[2]);
            field("forge.game.Game", "cardIdCounter").setInt(game, (Integer) snap[3]);
            field("forge.game.Game", "hiddenCardIdCounter").setInt(game, (Integer) snap[4]);
            restoreMemories((java.util.Map<Object, java.util.Map<Object, java.util.Set<Object>>>) snap[5]);
            restoreAiCache((com.google.common.collect.Multimap<Object, Object>) snap[6]);
            restoreControllers((java.util.Map<AiController, Object[]>) snap[7]);
        } catch (Exception e) {
            throw new IllegalStateException("learned-ai: cannot restore id counters: " + e, e);
        }
    }

    /**
     * H1 spell-choice hook: {@code approved} are the heuristics' approved plays in their order (index 0 is the stock
     * choice); "pass" is the extra last candidate. Returns the play to make, or null to pass priority.
     */
    public static forge.game.spellability.SpellAbility spellChoice(Player ai, List<forge.game.spellability.SpellAbility> approved) {
        if (approved.isEmpty()) return null;   // nothing playable: pass, nothing to decide
        boolean on = hookOn(ai, AiProps.LEARNED_SPELL_CHOICE);   // loads profile models if not yet loaded
        Scorer scorer = scorerFor(seatNumber(ai), SPELL_CHOICE);   // D36: per seat
        boolean learned = on && scorer != null;
        final double er = exploreRate(SPELL_CHOICE);
        boolean explore = !learned && er > 0 && forge.util.MyRandom.getRandom().nextDouble() < er;
        final int forced = countDecision(SPELL_CHOICE, seatNumber(ai), approved.size() + 1, ai.getGame().getPhaseHandler().getTurn());
        if (!LearnLog.enabled() && !learned && !explore && forced < 0) return approved.get(0);

        List<Player> seats = Features.seatsFrom(ai);
        int n = approved.size() + 1;
        int[] cands = new int[n];
        for (int i = 0; i < approved.size(); i++) cands[i] = i;
        cands[n - 1] = -1;   // -1 marks "pass"
        // observation only unless a model acts on them: the features are taken from a saved state that is put back
        final Object[] feats = observing(ai.getGame(), !learned, () -> {
            float[] fx = Features.state(ai);
            float[][] fa = new float[n][];
            int manaAvail = forge.ai.ComputerUtilMana.getAvailableManaEstimate(ai, false);
            for (int i = 0; i < approved.size(); i++) fa[i] = Features.spellCandidate(ai, approved.get(i), i, i == 0, seats, manaAvail);
            fa[n - 1] = Features.passCandidate(n - 1, manaAvail);
            return new Object[] {fx, fa};
        });
        float[] x = (float[]) feats[0];
        float[][] xa = (float[][]) feats[1];
        int chosen = 0;
        if (learned) {
            float[] s = scorer.score(x, xa);
            int best = 0;
            for (int i = 1; i < s.length; i++) if (s[i] > s[best]) best = i;
            if (s[best] - s[0] > margin) chosen = best;
        } else if (explore) {
            chosen = forge.util.MyRandom.getRandom().nextInt(n);
        }
        if (forced >= 0) chosen = forced;   // after the normal path, so the forced-same branch draws the same randomness
        if (LearnLog.enabled()) {
            int[] abs = new int[seats.size()];
            for (int i = 0; i < seats.size(); i++) abs[i] = seatNumber(seats.get(i));
            PhaseType ph = ai.getGame().getPhaseHandler().getPhase();
            LearnLog.decision(SPELL_CHOICE, seatNumber(ai), ai.getGame().getPhaseHandler().getTurn(), ph == null ? 0 : ph.ordinal(),
                    abs, cands, chosen, 0, forced >= 0 ? "fork" : learned ? "learned" : (explore ? "explore" : "stock"), modelId(learned, scorer), x, xa);
        }
        if (forced >= 0) forkReseed();
        return chosen == n - 1 ? null : approved.get(chosen);
    }

    /** 1-based seat number as used in SIMRESULT (registration order). */
    public static int seatNumber(Player p) {
        List<Player> reg = new ArrayList<>(p.getGame().getRegisteredPlayers());
        return reg.indexOf(p) + 1;
    }
}
