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
    /** How many heuristic-approved plays the spell-choice hook collects per priority (plus "pass"). */
    public static int spellCandidates = 4;

    /** Scores (state, candidate) pairs; null until a model is loaded (M3). */
    public interface Scorer {
        float[] score(float[] x, float[][] xa);
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Scorer> SCORERS = new java.util.concurrent.ConcurrentHashMap<>();
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

    /** Parses the {@code -fork} tokens: seat=<1-based> hook=<spell_choice|attack_target> index=<k> choose=<pos> seed=<s>. */
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
        if (!SPELL_CHOICE.equals(hook) && !ATTACK_TARGET.equals(hook))
            throw new IllegalArgumentException("fork: hook must be spell_choice or attack_target, got " + hook);
        forkSeat = seat; forkHook = hook; forkIndex = index; forkChoose = choose; forkSeed = seed;
        forkDone = false; forkConfigured = true;
        System.out.println("SIMNOTE fork armed seat=" + seat + " hook=" + hook + " index=" + index + " choose=" + choose + " seed=" + seed);
    }

    /** Counts this decision and returns the forced candidate position if it is the fork point, else -1. */
    private static synchronized int countDecision(String hook, int seat, int nCands, int turn) {
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

    /** Called after the forced decision is logged: reseeds the game's random source with the branch seed (0 = keep). */
    private static void forkReseed() {
        if (forkSeed == 0) return;
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

    public static void setScorer(String hook, Scorer s) { if (s == null) SCORERS.remove(hook); else SCORERS.put(hook, s); }
    public static Scorer scorer(String hook) { return SCORERS.get(hook); }

    public static void beginDeclare(Player ai) { DECLARING.set(ai); }
    public static void endDeclare() { DECLARING.remove(); }

    private static boolean hookOn(Player ai, AiProps prop) {
        if (!(ai.getController() instanceof PlayerControllerAi)) return false;
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        if (!aic.getBoolProperty(AiProps.LEARNED_HOOKS) || !aic.getBoolProperty(prop)) return false;
        ensureModelsLoaded(aic);
        return true;
    }

    private static volatile boolean profileModelsTried = false;
    // D20: scorers loaded from the profile (hook -> path) and the loaded models by path, so a per-game reset is cheap
    private static final java.util.Map<String, String> PROFILE_LOADED = new java.util.HashMap<>();
    private static final java.util.Map<String, Scorer> PROFILE_MODEL_CACHE = new java.util.HashMap<>();

    /**
     * D20: puts the profile-model state back to how a fresh process starts a game (nothing loaded, loaded on the first
     * hooked decision), so game k of a multi-game run plays like a standalone {@code -n 1} run of its seed. The first
     * hooked decision of a process finds no scorer (it is read before {@link #hookOn} loads it) and goes stock; with
     * this reset that happens in every game, not only the first one of the JVM. Models stay cached by path.
     */
    public static synchronized void resetProfileModels() {
        for (String hook : PROFILE_LOADED.keySet()) SCORERS.remove(hook);
        PROFILE_LOADED.clear();
        profileModelsTried = false;
    }

    /**
     * GUI and any run without {@code -model}: load the scorers named by the profile's LEARNED_MODEL
     * ("spell_choice=learned/spell_choice.onnx;attack_target=..."), paths relative to the AI profile folder
     * (res/ai) unless absolute. Done once per process, on the first hooked decision of a Learned seat.
     */
    private static void ensureModelsLoaded(AiController aic) {
        if (profileModelsTried) return;
        synchronized (LearnedHooks.class) {
            if (profileModelsTried) return;
            profileModelsTried = true;
            String spec = aic.getProperty(AiProps.LEARNED_MODEL);
            if (spec == null || spec.trim().isEmpty()) return;
            for (String part : spec.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String hook = part.substring(0, eq).trim(); String path = part.substring(eq + 1).trim();
                if (SCORERS.containsKey(hook)) continue;   // -model on the command line wins
                java.io.File f = new java.io.File(path);
                if (!f.isAbsolute() && forge.ai.AiProfileUtil.getProfileDir() != null) f = new java.io.File(forge.ai.AiProfileUtil.getProfileDir(), path);
                try {
                    Scorer cached = PROFILE_MODEL_CACHE.get(f.getPath());
                    if (cached == null) {
                        cached = new OnnxScorer(f.getPath());
                        PROFILE_MODEL_CACHE.put(f.getPath(), cached);
                        System.out.println("learned-ai: loaded " + hook + " model " + f.getPath());
                    }
                    SCORERS.put(hook, cached);
                    PROFILE_LOADED.put(hook, f.getPath());
                } catch (Exception e) {
                    System.err.println("learned-ai: could not load " + hook + " model " + f.getPath() + ": " + e);
                }
            }
        }
    }

    /**
     * Attack-target hook. Returns the learned choice, or null to keep the stock choice.
     * @param threat the stock rule's threat score per opponent (a feature)
     * @param stock the opponent the stock rule picked
     */
    public static Player attackTarget(Player ai, List<Player> opponents, Map<Player, Integer> threat, Player stock) {
        boolean declaring = DECLARING.get() == ai;
        Scorer scorer = SCORERS.get(ATTACK_TARGET);
        boolean learned = hookOn(ai, AiProps.LEARNED_ATTACK_TARGET) && scorer != null;
        final double er = exploreRate(ATTACK_TARGET);
        boolean explore = declaring && !learned && er > 0 && forge.util.MyRandom.getRandom().nextDouble() < er;
        if (!declaring || opponents.size() < 2) return null;
        final int forced = countDecision(ATTACK_TARGET, seatNumber(ai), opponents.size(), ai.getGame().getPhaseHandler().getTurn());
        if (!LearnLog.enabled() && !learned && !explore && forced < 0) return null;

        List<Player> seats = Features.seatsFrom(ai);
        float[] x = Features.state(ai);
        float[][] xa = new float[opponents.size()][];
        int[] cands = new int[opponents.size()];
        int stockIdx = -1;
        for (int i = 0; i < opponents.size(); i++) {
            Player o = opponents.get(i);
            cands[i] = seats.indexOf(o);
            if (o == stock) stockIdx = i;
            xa[i] = Features.attackCandidate(ai, o, seats, threat.getOrDefault(o, 0), o == stock);
        }
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
                    abs, cands, chosen, stockIdx, forced >= 0 ? "fork" : learned ? "learned" : (explore ? "explore" : "stock"), x, xa);
        }
        if (forced >= 0) forkReseed();
        return (learned || explore || forced >= 0) && chosen >= 0 ? opponents.get(chosen) : null;
    }

    /** Whether the spell-choice hook needs the list of approved plays (logging, exploration or a loaded model). */
    public static boolean spellChoiceActive(Player ai) {
        return LearnLog.enabled() || forkConfigured || exploreRate(SPELL_CHOICE) > 0 || (hookOn(ai, AiProps.LEARNED_SPELL_CHOICE) && SCORERS.get(SPELL_CHOICE) != null);
    }

    /**
     * H1 spell-choice hook: {@code approved} are the heuristics' approved plays in their order (index 0 is the stock
     * choice); "pass" is the extra last candidate. Returns the play to make, or null to pass priority.
     */
    public static forge.game.spellability.SpellAbility spellChoice(Player ai, List<forge.game.spellability.SpellAbility> approved) {
        if (approved.isEmpty()) return null;   // nothing playable: pass, nothing to decide
        Scorer scorer = SCORERS.get(SPELL_CHOICE);
        boolean learned = hookOn(ai, AiProps.LEARNED_SPELL_CHOICE) && scorer != null;
        final double er = exploreRate(SPELL_CHOICE);
        boolean explore = !learned && er > 0 && forge.util.MyRandom.getRandom().nextDouble() < er;
        final int forced = countDecision(SPELL_CHOICE, seatNumber(ai), approved.size() + 1, ai.getGame().getPhaseHandler().getTurn());
        if (!LearnLog.enabled() && !learned && !explore && forced < 0) return approved.get(0);

        List<Player> seats = Features.seatsFrom(ai);
        float[] x = Features.state(ai);
        int n = approved.size() + 1;
        float[][] xa = new float[n][];
        int[] cands = new int[n];
        int manaAvail = forge.ai.ComputerUtilMana.getAvailableManaEstimate(ai, false);
        for (int i = 0; i < approved.size(); i++) { xa[i] = Features.spellCandidate(ai, approved.get(i), i, i == 0, seats, manaAvail); cands[i] = i; }
        xa[n - 1] = Features.passCandidate(n - 1, manaAvail); cands[n - 1] = -1;   // -1 marks "pass"
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
                    abs, cands, chosen, 0, forced >= 0 ? "fork" : learned ? "learned" : (explore ? "explore" : "stock"), x, xa);
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
