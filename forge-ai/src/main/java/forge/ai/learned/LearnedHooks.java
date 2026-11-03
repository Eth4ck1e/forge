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

    /** Scores (state, candidate) pairs; null until a model is loaded (M3). */
    public interface Scorer {
        float[] score(float[] x, float[][] xa);
    }

    private static volatile Scorer scorer;
    /**
     * Exploration for data generation (M4 off-policy fix): with this probability a stock-piloted seat attacks a
     * uniformly random candidate instead of the stock rule's pick, and the record is logged with pilot "explore" and
     * the propensity 1/n. Zero in evaluation runs.
     */
    public static volatile double exploreRate = 0.0;
    private static final ThreadLocal<Player> DECLARING = new ThreadLocal<>();

    private LearnedHooks() {}

    public static void setScorer(Scorer s) { scorer = s; }

    public static void beginDeclare(Player ai) { DECLARING.set(ai); }
    public static void endDeclare() { DECLARING.remove(); }

    private static boolean hookOn(Player ai, AiProps prop) {
        if (!(ai.getController() instanceof PlayerControllerAi)) return false;
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        return aic.getBoolProperty(AiProps.LEARNED_HOOKS) && aic.getBoolProperty(prop);
    }

    /**
     * Attack-target hook. Returns the learned choice, or null to keep the stock choice.
     * @param threat the stock rule's threat score per opponent (a feature)
     * @param stock the opponent the stock rule picked
     */
    public static Player attackTarget(Player ai, List<Player> opponents, Map<Player, Integer> threat, Player stock) {
        boolean declaring = DECLARING.get() == ai;
        boolean learned = hookOn(ai, AiProps.LEARNED_ATTACK_TARGET) && scorer != null;
        boolean explore = declaring && !learned && exploreRate > 0 && forge.util.MyRandom.getRandom().nextDouble() < exploreRate;
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
            for (int i = 0; i < s.length; i++) if (chosen < 0 || s[i] > s[chosen]) chosen = i;
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

    /** 1-based seat number as used in SIMRESULT (registration order). */
    public static int seatNumber(Player p) {
        List<Player> reg = new ArrayList<>(p.getGame().getRegisteredPlayers());
        return reg.indexOf(p) + 1;
    }
}
