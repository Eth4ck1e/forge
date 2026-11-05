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
                    SCORERS.put(hook, new OnnxScorer(f.getPath()));
                    System.out.println("learned-ai: loaded " + hook + " model " + f.getPath());
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
        if (!declaring || (!LearnLog.enabled() && !learned && !explore) || opponents.size() < 2) return null;

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
        if (LearnLog.enabled()) {
            int[] abs = new int[seats.size()];
            for (int i = 0; i < seats.size(); i++) abs[i] = seatNumber(seats.get(i));
            PhaseType ph = ai.getGame().getPhaseHandler().getPhase();
            LearnLog.decision(ATTACK_TARGET, seatNumber(ai), ai.getGame().getPhaseHandler().getTurn(), ph == null ? 0 : ph.ordinal(),
                    abs, cands, chosen, stockIdx, learned ? "learned" : (explore ? "explore" : "stock"), x, xa);
        }
        return (learned || explore) && chosen >= 0 ? opponents.get(chosen) : null;
    }

    /** Whether the spell-choice hook needs the list of approved plays (logging, exploration or a loaded model). */
    public static boolean spellChoiceActive(Player ai) {
        return LearnLog.enabled() || exploreRate(SPELL_CHOICE) > 0 || (hookOn(ai, AiProps.LEARNED_SPELL_CHOICE) && SCORERS.get(SPELL_CHOICE) != null);
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
        if (!LearnLog.enabled() && !learned && !explore) return approved.get(0);

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
        if (LearnLog.enabled()) {
            int[] abs = new int[seats.size()];
            for (int i = 0; i < seats.size(); i++) abs[i] = seatNumber(seats.get(i));
            PhaseType ph = ai.getGame().getPhaseHandler().getPhase();
            LearnLog.decision(SPELL_CHOICE, seatNumber(ai), ai.getGame().getPhaseHandler().getTurn(), ph == null ? 0 : ph.ordinal(),
                    abs, cands, chosen, 0, learned ? "learned" : (explore ? "explore" : "stock"), x, xa);
        }
        return chosen == n - 1 ? null : approved.get(chosen);
    }

    /** 1-based seat number as used in SIMRESULT (registration order). */
    public static int seatNumber(Player p) {
        List<Player> reg = new ArrayList<>(p.getGame().getRegisteredPlayers());
        return reg.indexOf(p) + 1;
    }
}
