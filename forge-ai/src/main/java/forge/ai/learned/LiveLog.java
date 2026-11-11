package forge.ai.learned;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;

/**
 * learned-ai GUI play: {@code -Dlearned.learnlog=<dir>} makes every GUI game write the sims' observation-only decision
 * records into its own file {@code <dir>/live-<yyyyMMdd-HHmmss>-g<gameId>.jsonl.gz}, ending with one
 * {@code game_result} record (the SIMRESULT fields plus, per seat, {@code human} and {@code profile}). HostedMatch calls
 * {@link #beginGame} before the game starts and {@link #endGame} when it is over. GUI games are not seeded: the
 * records' {@code seed} and {@code gseed} carry the game id (start time in ms), not an RNG seed; a live game cannot be
 * replayed. Hidden information: a human seat gets no turn_end record (its hand), see ManaView.onCleanup; AI seats'
 * records follow the D39 rules as in the sims. Absent property = nothing happens.
 */
public final class LiveLog {
    private LiveLog() {}

    private static File current;
    private static long started;

    public static boolean wanted() {
        String d = System.getProperty("learned.learnlog");
        return d != null && !d.trim().isEmpty();
    }

    /** opens the game's file and prints its path; no-op without the property or for simulation copies */
    public static synchronized void beginGame(Game game) {
        if (!wanted() || game == null || game.isSimulationCopy()) return;
        started = System.currentTimeMillis();
        File dir = new File(System.getProperty("learned.learnlog").trim().replaceFirst("^~", System.getProperty("user.home")));
        dir.mkdirs();
        File f = new File(dir, "live-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(started)) + "-g" + started + ".jsonl.gz");
        try {
            LearnLog.openFile(f, started);
            LearnLog.beginGame(1, started);
            current = f;
            System.out.println("SIMNOTE learned live learnlog " + f.getAbsolutePath());
            System.out.println("SIMNOTE learned " + LiveUpload.gamesLine());   // D46: games played k / N
        } catch (IOException | RuntimeException e) {
            current = null;
            System.out.println("SIMNOTE learned live learnlog failed: " + e);
        }
    }

    /** writes the game_result record and closes the file */
    public static synchronized void endGame(Game game) {
        if (current == null) return;
        long ended = System.currentTimeMillis();
        List<LiveUpload.Seat> seats = null;
        int turns = 0;
        String reason = null;
        try {
            LearnLog.raw("game_result", resultJson(game, ended - started));
            LearnLog.endGame();
            seats = seats(game);
            turns = game.getPhaseHandler() == null ? 0 : game.getPhaseHandler().getTurn();
            reason = game.getOutcome() == null ? null : String.valueOf(game.getOutcome().getWinCondition());
        } catch (RuntimeException e) {
            System.out.println("SIMNOTE learned live learnlog result failed: " + e);
        } finally {
            LearnLog.close();
            System.out.println("SIMNOTE learned live learnlog closed " + current.getAbsolutePath());
        }
        File f = current;
        current = null;
        if (LiveUpload.url() == null) return;   // D46: upload only when configured
        try {
            String m = LiveUpload.manifest(LearnedConfig.build(), System.getProperty("learned.configId"), System.getProperty("learned.playerId"),
                    started, started, ended, turns, reason, seats == null ? new ArrayList<>() : seats, LiveUpload.sha256(f), f.length());
            LiveUpload.gameClosed(f, m);
        } catch (IOException | RuntimeException e) {
            System.out.println("SIMNOTE learned upload could not build manifest for " + f + ": " + e);
        }
    }

    /** manifest seats with places (LiveUpload.places) */
    static List<LiveUpload.Seat> seats(Game g) {
        List<LiveUpload.Seat> out = new ArrayList<>();
        List<Integer> turns = new ArrayList<>();
        for (Player p : g.getRegisteredPlayers()) {
            LiveUpload.Seat s = new LiveUpload.Seat();
            s.name = p.getName();
            Deck d = p.getRegisteredPlayer() == null ? null : p.getRegisteredPlayer().getDeck();
            s.deck = d == null ? null : d.getName();
            s.profile = p.getLobbyPlayer() instanceof LobbyPlayerAi ? ((LobbyPlayerAi) p.getLobbyPlayer()).getAiProfile() : null;
            s.human = isHuman(p);
            PlayerOutcome po = p.getOutcome();
            s.result = po == null ? "unknown" : po.hasWon() ? "win" : po.lossState == GameLossReason.IntentionalDraw ? "draw" : "loss";
            out.add(s);
            turns.add(p.getOutcomeTurn());
        }
        LiveUpload.places(out, turns);
        return out;
    }

    /** a human (non-AI) seat: its hidden zones are never recorded */
    public static boolean isHuman(Player p) { return !(p.getController() instanceof PlayerControllerAi); }

    /** SIMRESULT-equivalent body (SimulateMatch.simResultLine fields) with human / profile per seat */
    static String resultJson(Game g, long durationMs) {
        StringBuilder sb = new StringBuilder(512);
        int turns = g.getPhaseHandler() == null ? 0 : g.getPhaseHandler().getTurn();
        sb.append("\"live\":1,\"turns\":").append(turns).append(",\"durationMs\":").append(durationMs);
        GameOutcome outcome = g.getOutcome();
        sb.append(",\"over\":").append(g.isGameOver() ? 1 : 0);
        sb.append(",\"endReason\":"); ManaView.str(sb, outcome == null ? null : String.valueOf(outcome.getWinCondition()));
        sb.append(",\"draw\":").append(outcome != null && outcome.isDraw());
        sb.append(",\"players\":[");
        int seat = 0;
        for (Player p : g.getRegisteredPlayers()) {
            if (seat++ > 0) sb.append(',');
            PlayerOutcome po = p.getOutcome();
            String result = po == null ? "unknown" : po.hasWon() ? "win" : po.lossState == GameLossReason.IntentionalDraw ? "draw" : "loss";
            sb.append("{\"seat\":").append(seat).append(",\"name\":"); ManaView.str(sb, p.getName());
            Deck d = p.getRegisteredPlayer() == null ? null : p.getRegisteredPlayer().getDeck();
            sb.append(",\"deck\":"); ManaView.str(sb, d == null ? null : d.getName());
            sb.append(",\"human\":").append(isHuman(p) ? 1 : 0);
            String prof = p.getLobbyPlayer() instanceof LobbyPlayerAi ? ((LobbyPlayerAi) p.getLobbyPlayer()).getAiProfile() : null;
            sb.append(",\"profile\":"); ManaView.str(sb, prof);
            sb.append(",\"result\":\"").append(result).append('"');
            sb.append(",\"reason\":"); if (po == null || po.lossState == null) sb.append("null"); else ManaView.str(sb, po.lossState.name());
            sb.append(",\"turn\":").append(p.getOutcomeTurn()).append(",\"life\":").append(p.getLife()).append('}');
        }
        sb.append(']');
        return sb.toString();
    }
}
