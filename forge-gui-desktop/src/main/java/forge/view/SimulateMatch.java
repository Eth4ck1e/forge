package forge.view;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.time.StopWatch;

import forge.LobbyPlayer;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.FlatMonteCarloStats;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.tournament.system.AbstractTournament;
import forge.gamemodes.tournament.system.TournamentBracket;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.gamemodes.tournament.system.TournamentSwiss;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Lang;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.WordUtil;
import forge.util.storage.IStorage;

public class SimulateMatch {
    /** Set by -mc: null leaves the game default (off, as the GUI's preference is only applied by HostedMatch). */
    private static Boolean flatMonteCarlo = null;
    private static Boolean flatMonteCarloMax = null;

    public static void simulate(String[] args) {
        FModel.initialize(null, null);

        System.out.println("Simulation mode");
        if (args.length < 4) {
            argumentHelp();
            return;
        }

        final Map<String, List<String>> params = new HashMap<>();
        List<String> options = null;

        for (int i = 1; i < args.length; i++) {
            // "sim" is in the 0th slot
            final String a = args[i];

            if (a.charAt(0) == '-') {
                if (a.length() < 2) {
                    System.err.println("Error at argument " + a);
                    argumentHelp();
                    return;
                }

                options = new ArrayList<>();
                final List<String> prev = "-model".equals(a) ? params.get("model") : null;   // learned-ai D36: repeated -model flags merge
                if (prev != null) {
                    options = prev;
                } else {
                    params.put(a.substring(1), options);
                }
            } else if (options != null) {
                options.add(a);
            } else {
                System.err.println("Illegal parameter usage");
                return;
            }
        }

        String deckDir = null;
        if (params.containsKey("D")) {
            deckDir = params.get("D").get(0);
        }

        int nGames = 1;
        if (params.containsKey("n")) {
            // Number of games should only be a single string
            nGames = Integer.parseInt(params.get("n").get(0));
        }

        int matchSize = 0;
        if (params.containsKey("m")) {
            // Match size ("best of X games")
            matchSize = Integer.parseInt(params.get("m").get(0));
        }

        boolean outputGamelog = !params.containsKey("q");

        // Flat Monte Carlo is a match preference that only the GUI applies (HostedMatch); without this switch a
        // simulation always runs the plain heuristic AI, whatever the preference says.
        flatMonteCarlo = null;
        flatMonteCarloMax = null;
        if (params.containsKey("mc")) {
            final List<String> mcValues = params.get("mc");
            final String mcValue = mcValues.isEmpty() ? "on" : mcValues.get(0);
            if ("on".equalsIgnoreCase(mcValue) || "true".equalsIgnoreCase(mcValue)) {
                flatMonteCarlo = Boolean.TRUE;
            } else if ("max".equalsIgnoreCase(mcValue)) {
                flatMonteCarlo = Boolean.TRUE;
                flatMonteCarloMax = Boolean.TRUE; // the strength a desktop match gets automatically
            } else if ("off".equalsIgnoreCase(mcValue) || "false".equalsIgnoreCase(mcValue)) {
                flatMonteCarlo = Boolean.FALSE;
            } else {
                System.out.println("Unknown value for -mc (use on, max or off): " + mcValue);
                return;
            }
        }
        FlatMonteCarloStats.reset();
        // learned-ai: must be known before the AI players are created below
        simOptions = null;
        if (params.containsKey("sim")) {
            final String mode = params.get("sim").get(0);
            simOptions = java.util.EnumSet.of("full".equalsIgnoreCase(mode) ? forge.ai.AIOption.USE_FULL_SIMULATION : forge.ai.AIOption.USE_HYBRID_SIMULATION);
        }

        Long seed = null;
        if (params.containsKey("s")) {
            seed = Long.parseLong(params.get("s").get(0));
            MyRandom.setRandom(new Random(seed));
        }

        GameType type = GameType.Constructed;
        if (params.containsKey("f")) {
            type = GameType.valueOf(WordUtil.capitalize(params.get("f").get(0)));
        }

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));

        if (matchSize != 0) {
            rules.setGamesPerMatch(matchSize);
        }

        if (params.containsKey("t")) {
            simulateTournament(params, rules, outputGamelog);
            System.out.flush();
            return;
        }

        List<RegisteredPlayer> pp = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        int i = 1;

        // Optional AI profile per player, in the same order as the decks. Lets a run pit one set of
        // AI settings against another, which is the only way to tell from the results whether an AI
        // change actually helped.
        List<String> aiProfiles = params.get("a");
        if (aiProfiles != null) {
            for (String profile : aiProfiles) {
                if (!AiProfileUtil.getProfilesDisplayList().contains(profile)) {
                    System.out.println(TextUtil.concatNoSpace("Unknown AI profile - ", profile,
                            ". Available profiles: ", String.join(", ", AiProfileUtil.getProfilesDisplayList())));
                    return;
                }
            }
        }

        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, type, deckDir);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }
                if (i > 1) {
                    sb.append(" vs ");
                }
                String profile = aiProfiles != null && aiProfiles.size() >= i ? aiProfiles.get(i - 1) : "";
                String name = TextUtil.concatNoSpace("Ai(", String.valueOf(i), ")-", d.getName());
                sb.append(name);
                if (!profile.isEmpty()) {
                    sb.append(" [").append(profile).append("]");
                }

                RegisteredPlayer rp;

                if (type.equals(GameType.Commander)) {
                    rp = RegisteredPlayer.forCommander(d);
                } else {
                    rp = new RegisteredPlayer(d);
                }
                rp.setPlayer(simOptions == null ? GamePlayerUtil.createAiPlayer(name, i - 1, profile)
                        : GamePlayerUtil.createAiPlayer(name, i - 1, i - 1, simOptions, profile));
                pp.add(rp);
                i++;
            }
        }

        if (params.containsKey("c")) {
            rules.setSimTimeout(Integer.parseInt(params.get("c").get(0)));
        }
        // learned-ai: -r caps the game at R table rounds (R * number of players engine turns); capped games are draws
        if (params.containsKey("r")) {
            int rounds = Integer.parseInt(params.get("r").get(0));
            rules.setTurnCap(rounds * pp.size());
        }
        lastSeed = seed;
        exitOnTimeout = params.containsKey("x");
        if (params.containsKey("stop")) {
            stopFile = new File(params.get("stop").get(0));
        }
        census = params.containsKey("census");
        forge.ai.learned.DecisionCensus.enabled = census;
        // learned-ai M1: -learnlog <dir> writes one gzip JSON-lines decision log per sim process
        if (params.containsKey("learnlog")) {
            try {
                forge.ai.learned.LearnLog.open(new File(params.get("learnlog").get(0)), seed == null ? 0L : seed.longValue());
            } catch (java.io.IOException e) {
                throw new RuntimeException("learnlog: " + e, e);
            }
        }
        if (params.containsKey("at")) {
            aiTimeoutSeconds = Integer.parseInt(params.get("at").get(0));
        }
        // learned-ai M3: -model <onnx> loads the scorer; seats with the Learned profile (-a Learned,...) then take the
        // hooked decisions from it, every other seat keeps the stock heuristics
        // learned-ai M4: -explore <rate> makes stock seats attack a random opponent with that probability (data generation only)
        if (params.containsKey("explore")) {
            for (final String spec : params.get("explore")) {   // "<hook>=<rate>"; a bare rate means attack_target
                final int eq = spec.indexOf('=');
                final String hook = eq < 0 ? forge.ai.learned.LearnedHooks.ATTACK_TARGET : spec.substring(0, eq);
                final double rate = Double.parseDouble(eq < 0 ? spec : spec.substring(eq + 1));
                forge.ai.learned.LearnedHooks.setExploreRate(hook, rate);
                System.out.println("SIMNOTE explore rate " + hook + " " + rate);
            }
        }
        // learned-ai D20: -fork seat=<n> hook=<h> index=<k> choose=<pos> seed=<s> (one flag, all tokens; game 1 only, run -n 1 -s <gameSeed>)
        if (params.containsKey("fork")) {
            forge.ai.learned.LearnedHooks.configureFork(params.get("fork"));
        }
        // learned-ai D22 phase 2: -combat <stock|solver|shadow> overrides LEARNED_COMBAT of every Learned seat (= -Dlearned.combat);
        // -impact <card_impact.json|csv> gives the solver its value bonus and protected pieces (= -Dlearned.impact)
        if (params.containsKey("combat")) {
            forge.ai.learned.LearnedCombat.setOverride(params.get("combat").get(0));
        }
        if (params.containsKey("impact")) {
            forge.ai.learned.LearnedCombat.setImpactPath(params.get("impact").get(0));
        }
        if (params.containsKey("spellcands")) {
            forge.ai.learned.LearnedHooks.spellCandidates = Integer.parseInt(params.get("spellcands").get(0));
        }
        if (params.containsKey("margin")) {
            forge.ai.learned.LearnedHooks.margin = Float.parseFloat(params.get("margin").get(0));
            System.out.println("SIMNOTE learned margin " + forge.ai.learned.LearnedHooks.margin);
        }
        if (params.containsKey("model")) {
            try {
                for (String spec : params.get("model")) {
                    // "[seat<N>:]<hook>=<path>" or "[seat<N>:]<hook>=random"; a bare path means attack_target (the first hook).
                    // learned-ai D36: a seat<N>: prefix gives that seat (1-based, SIMRESULT order) its own model; it still
                    // needs a Learned-type profile in -a. Unprefixed specs serve every Learned seat without a seat model.
                    int seatN = 0;
                    final java.util.regex.Matcher sm = java.util.regex.Pattern.compile("^seat(\\d+):(.*)$").matcher(spec);
                    if (sm.matches()) {
                        seatN = Integer.parseInt(sm.group(1));
                        spec = sm.group(2);
                        if (seatN < 1) {
                            throw new IllegalArgumentException("seat numbers start at 1: " + spec);
                        }
                    }
                    final int eq = spec.indexOf('=');
                    final String hook = eq < 0 ? forge.ai.learned.LearnedHooks.ATTACK_TARGET : spec.substring(0, eq);
                    final String modelPath = eq < 0 ? spec : spec.substring(eq + 1);
                    final forge.ai.learned.LearnedHooks.Scorer sc;
                    if ("random".equals(modelPath)) {
                        // diagnostic: Learned seats pick a uniformly random candidate (calibrates offline policy evaluation)
                        sc = forge.ai.learned.LearnedHooks.named("random", (x, xa) -> {
                            final float[] r = new float[xa.length];
                            for (int k = 0; k < r.length; k++) r[k] = forge.util.MyRandom.getRandom().nextFloat();
                            return r;
                        });
                    } else {
                        sc = forge.ai.learned.LearnedHooks.loadModel(modelPath);
                    }
                    if (seatN > 0) {
                        forge.ai.learned.LearnedHooks.setSeatScorer(seatN, hook, sc);
                        System.out.println("SIMNOTE learned model seat" + seatN + " " + hook + " " + ("random".equals(modelPath) ? "random" : modelPath) + " " + sc.id());
                    } else {
                        forge.ai.learned.LearnedHooks.setScorer(hook, sc);
                        System.out.println("SIMNOTE learned model " + hook + " " + ("random".equals(modelPath) ? "random" : modelPath) + " " + sc.id());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("model: " + e, e);
            }
        }

        sb.append(" - ").append(Lang.nounWithNumeral(nGames, "game")).append(" of ").append(type);
        if (seed != null) {
            sb.append(" seed ").append(seed);
        }

        System.out.println(sb);

        Match mc = new Match(rules, pp, "Test");

        if (matchSize != 0) {
            int iGame = 0;
            while (!mc.isMatchOver()) {
                // play games until the match ends
                gameSeed = iGame == 0 ? lastSeed : null;   // learned-ai D20: later games of a match are not replayable
                simulateSingleMatch(mc, iGame, outputGamelog);
                iGame++;
            }
        } else {
            for (int iGame = 0; iGame < nGames; iGame++) {
                if (stopFile != null && stopFile.exists()) {
                    System.out.println("SIMNOTE stop file present, exiting after " + iGame + " games");
                    break;
                }
                // learned-ai D20: every game of a -n run is a standalone first game: fresh Match (no previous outcome
                // choosing the starting player, no sideboarding), engine id counters back to their pre-game-1 values
                // and MyRandom reseeded with the per-game seed, so `-s <gameSeed> -n 1` replays game k exactly
                beginStandaloneGame(iGame);
                simulateSingleMatch(iGame == 0 ? mc : new Match(rules, pp, "Test"), iGame, outputGamelog);
            }
        }

        if (Boolean.TRUE.equals(flatMonteCarlo)) {
            System.out.println(FlatMonteCarloStats.summary());
        }
        System.out.flush();
    }

    private static void argumentHelp() {
        System.out.println("Syntax: forge.exe sim -d <deck1[.dck]> ... <deckX[.dck]> -D [D] -n [N] -m [M] -t [T] -p [P] -f [F] -s [S] -a [A] -mc [on|off] -q");
        System.out.println("\tsim - stands for simulation mode");
        System.out.println("\tdeck1 (or deck2,...,X) - constructed deck name or filename (has to be quoted when contains multiple words)");
        System.out.println("\tdeck is treated as file if it ends with a dot followed by three numbers or letters");
        System.out.println("\tD - absolute directory to load decks from");
        System.out.println("\tN - number of games, defaults to 1 (Ignores match setting)");
        System.out.println("\tM - Play full match of X games, typically 1,3,5 games. (Optional, overrides N)");
        System.out.println("\tT - Type of tournament to run with all provided decks (Bracket, RoundRobin, Swiss)");
        System.out.println("\tP - Amount of players per match (used only with Tournaments, defaults to 2)");
        System.out.println("\tF - format of games, defaults to constructed");
        System.out.println("\tS - RNG seed for simulation (a long). Game 1 uses it as is; game k >= 2 of a -n run uses (S << 32) | (k - 1), printed as gameSeed in SIMRESULT; -s <gameSeed> -n 1 replays that game.");
        System.out.println("\tA - AI profile per player, in the same order as the decks (e.g. -a Default Experimental)");
        System.out.println("\tc - Clock flag. Set the maximum time in seconds before calling the match a draw, defaults to 120.");
        System.out.println("\tmc - Flat Monte Carlo AI refinement for this run, on, max or off (default off in simulations; max = desktop strength). Prints timing statistics at the end when on.");
        System.out.println("\tq - Quiet flag. Output just the game result, not the entire game log.");
        System.out.println("\tcensus - Print a SIMCENSUS json line per game: AI decisions by category and evaluator/simulator calls per category.");
        System.out.println("\tsim - full|hybrid: use the SpellAbilityPicker simulation mode for every AI seat (independent of -mc).");
        System.out.println("\tstop - Path of a stop file; the run exits before the next game once it exists.");
        System.out.println("\tx - Exit the process (code 3) after a wall-clock timeout; the cancelled game thread cannot be stopped.");
        System.out.println("\tat - Per-decision AI time budget in seconds (default 5).");
        System.out.println("\tfork - seat=<n> hook=<spell_choice|attack_target> index=<k> choose=<pos> seed=<s>: force candidate <pos> at the k-th (0-based) logged decision of that seat and hook, then reseed (0 = keep). One -fork flag with all tokens; game 1 only (use -n 1 -s <gameSeed> for a later game). hook=attack_assign: the k-th combat_attack record of a solver seat; pos 0..N-1 = the solver's top-N lines, N = the stock line.");
        System.out.println("\tcombat - stock|solver|shadow: attack declaration of every Learned seat (shadow = stock plays, the solver is logged with status shadow) (overrides LEARNED_COMBAT; same as -Dlearned.combat)");
        System.out.println("\timpact - card-impact table (eval/card_impact.py JSON, or CSV name,threat_shrunk) for the combat solver's values and protected pieces");
        System.out.println("\tr - Turn cap in table rounds (rounds * players engine turns); games reaching it are recorded as draws. One SIMRESULT json line is printed per game.");
    }

    // learned-ai: run seed, echoed into every SIMRESULT line
    private static Long lastSeed = null;
    // learned-ai D20: seed of the game being played (null when unseeded, or for a non-first game of -m / tournament)
    private static Long gameSeed = null;

    /** learned-ai D20: game 1 keeps the run seed; game k >= 2 gets (runSeed << 32) | (k - 1). */
    public static long gameSeedFor(final long runSeed, final int game) {
        return game <= 1 ? runSeed : (runSeed << 32) | (game - 1);
    }

    // learned-ai D20: static id counters of the engine. SpellAbility ids feed hashCode (hash-set iteration order), so
    // a later game in the same JVM would not match a standalone run of it unless these start where game 1 started.
    private static final String[][] ID_COUNTERS = {
            {"forge.game.Game", "maxId"},
            {"forge.game.spellability.SpellAbility", "maxId"},
            {"forge.game.spellability.SpellAbilityStackInstance", "maxId"},
            {"forge.game.trigger.Trigger", "maxId"},
            {"forge.game.replacement.ReplacementEffect", "maxId"},
            {"forge.game.staticability.StaticAbility", "maxId"},
            {"forge.game.combat.CombatView", "nextId"},
    };
    private static int[] idCountersAtGame1 = null;

    private static void beginStandaloneGame(final int iGame) {
        try {
            if (iGame == 0) {
                idCountersAtGame1 = new int[ID_COUNTERS.length];
            }
            for (int k = 0; k < ID_COUNTERS.length; k++) {
                final java.lang.reflect.Field f = Class.forName(ID_COUNTERS[k][0]).getDeclaredField(ID_COUNTERS[k][1]);
                f.setAccessible(true);
                if (iGame == 0) {
                    idCountersAtGame1[k] = f.getInt(null);
                } else {
                    f.setInt(null, idCountersAtGame1[k]);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("id counters: " + e, e);
        }
        if (iGame > 0) {
            forge.ai.learned.LearnedHooks.resetProfileModels();   // profile models reload when the seats are created
        }
        gameSeed = lastSeed == null ? null : gameSeedFor(lastSeed, iGame + 1);
        if (gameSeed != null) {
            MyRandom.setRandom(new Random(gameSeed));
        }
    }
    // learned-ai: -x makes the process exit (code 3) after a wall-clock timeout instead of continuing with a runaway thread
    private static boolean exitOnTimeout = false;
    // learned-ai: -at <seconds> overrides the per-decision AI budget (Game.AI_TIMEOUT, default 5)
    private static Integer aiTimeoutSeconds = null;
    // learned-ai: -stop <file>: before each game, exit (code 0) if this file exists. Lets a batch runner oversubscribe
    // workers and stop dispatching once enough games are done, without cutting any game short.
    private static File stopFile = null;
    // learned-ai: -census prints a SIMCENSUS json line per game (decision counts by category, evaluator/simulator use)
    private static boolean census = false;
    // learned-ai: -sim full|hybrid gives every AI seat the older SpellAbilityPicker simulation mode (AIOption)
    private static java.util.Set<forge.ai.AIOption> simOptions = null;

    private static String jsonStr(final String v) {
        if (v == null) {
            return "null";
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    /**
     * learned-ai: one machine-readable line per finished game, for the evaluation harness. Everything here is read
     * from the finished Game object; nothing in the game is changed.
     */
    private static String simResultLine(final Game g, final int iGame, final long durationMs, final int exceptions) {
        final StringBuilder sb = new StringBuilder("SIMRESULT {");
        sb.append("\"game\":").append(iGame + 1);
        sb.append(",\"seed\":").append(lastSeed == null ? "null" : lastSeed.toString());
        sb.append(",\"gameSeed\":").append(gameSeed == null ? "null" : gameSeed.toString());   // learned-ai D20
        final int turns = g.getPhaseHandler() == null ? 0 : g.getPhaseHandler().getTurn();
        final int cap = g.getRules().getTurnCap();
        sb.append(",\"turns\":").append(turns);
        sb.append(",\"turnCap\":").append(cap);
        sb.append(",\"capped\":").append(cap > 0 && turns > cap);
        sb.append(",\"durationMs\":").append(durationMs);
        sb.append(",\"exceptions\":").append(exceptions);
        final forge.game.GameOutcome outcome = g.getOutcome();
        sb.append(",\"endReason\":").append(outcome == null ? "null" : jsonStr(String.valueOf(outcome.getWinCondition())));
        sb.append(",\"draw\":").append(outcome != null && outcome.isDraw());
        sb.append(",\"players\":[");
        int seat = 0;
        for (final forge.game.player.Player p : g.getRegisteredPlayers()) {
            if (seat > 0) {
                sb.append(',');
            }
            final forge.game.player.PlayerOutcome po = p.getOutcome();
            final String result = po == null ? "unknown" : po.hasWon() ? "win"
                    : po.lossState == forge.game.player.GameLossReason.IntentionalDraw ? "draw" : "loss";
            sb.append('{');
            sb.append("\"seat\":").append(seat + 1);
            sb.append(",\"name\":").append(jsonStr(p.getName()));
            final Deck d = p.getRegisteredPlayer() == null ? null : p.getRegisteredPlayer().getDeck();
            sb.append(",\"deck\":").append(jsonStr(d == null ? null : d.getName()));
            sb.append(",\"result\":").append(jsonStr(result));
            sb.append(",\"reason\":").append(po == null || po.lossState == null ? "null" : jsonStr(po.lossState.name()));
            sb.append(",\"turn\":").append(p.getOutcomeTurn());
            sb.append(",\"life\":").append(p.getLife());
            sb.append('}');
            seat++;
        }
        sb.append("]}");
        return sb.toString();
    }

    public static void simulateSingleMatch(final Match mc, int iGame, boolean outputGamelog) {
        final StopWatch sw = new StopWatch();
        sw.start();

        final Game g1 = mc.createGame();
        g1.setNoGUIUser();
        if (aiTimeoutSeconds != null) {
            g1.AI_TIMEOUT = aiTimeoutSeconds;
        }
        if (flatMonteCarlo != null) {
            g1.AI_FLAT_MONTE_CARLO = flatMonteCarlo;
            g1.AI_FLAT_MONTE_CARLO_MAX = Boolean.TRUE.equals(flatMonteCarloMax);
        }
        int exceptions = 0;
        boolean timedOut = false;
        // will run match in the same thread
        forge.ai.learned.LearnLog.beginGame(iGame + 1, gameSeed == null ? 0L : gameSeed.longValue());   // SIMRESULT numbers games from 1
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
                sw.stop();
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("Stopping slow match as draw");
            timedOut = true;
        } catch (Exception | StackOverflowError e) {
            e.printStackTrace();
            exceptions++;
        } finally {
            if (sw.isStarted()) {
                sw.stop();
            }
            // learned-ai: a game cut short by the wall clock or an exception is a draw for everyone still in it;
            // without this the survivors are scored as winners by Player.onGameOver()
            if (!g1.isGameOver()) {
                for (final forge.game.player.Player p : g1.getPlayers()) {
                    if (p.getOutcome() == null) {
                        p.intentionalDraw();
                    }
                }
            }
            g1.setGameOver(GameEndReason.Draw);
        }

        List<GameLogEntry> log;
        if (outputGamelog) {
            log = g1.getGameLog().getLogEntries(null);
        } else {
            log = g1.getGameLog().getLogEntries(GameLogEntryType.MATCH_RESULTS);
        }
        Collections.reverse(log);
        for (GameLogEntry l : log) {
            System.out.println(l);
        }

        // If both players life totals to 0 in a single turn, the game should end in a draw
        if (g1.getOutcome().isDraw()) {
            System.out.printf("\nGame Result: Game %d ended in a Draw! Took %d ms.%n", 1 + iGame, sw.getTime());
        } else {
            System.out.printf("\nGame Result: Game %d ended in %d ms. %s has won!\n%n", 1 + iGame, sw.getTime(), g1.getOutcome().getWinningLobbyPlayer().getName());
        }
        if (timedOut) {
            System.out.println("SIMNOTE wall-clock timeout");
        }
        System.out.println(simResultLine(g1, iGame, sw.getTime(), exceptions));
        if (census) {
            System.out.println("SIMCENSUS " + forge.ai.learned.DecisionCensus.snapshotJson(true));
        }
        forge.ai.learned.LearnedHooks.forkGameEnd();   // learned-ai D20
        forge.ai.learned.LearnLog.endGame();
        // learned-ai D36: totals as before (cumulative per process), plus one entry per loaded model
        final List<forge.ai.learned.OnnxScorer> models = forge.ai.learned.LearnedHooks.loadedModels();
        if (!models.isEmpty()) {
            long calls = 0;
            double micros = 0;
            final StringBuilder per = new StringBuilder();
            for (final forge.ai.learned.OnnxScorer m : models) {
                calls += m.calls();
                micros += m.meanMicros() * m.calls();
                per.append(per.length() == 0 ? "" : ",").append(String.format(java.util.Locale.ROOT,
                        "{\"id\":%s,\"calls\":%d,\"meanMicros\":%.1f}", jsonStr(m.id()), m.calls(), m.meanMicros()));
            }
            System.out.println(String.format(java.util.Locale.ROOT, "SIMMODEL {\"calls\":%d,\"meanMicros\":%.1f,\"models\":[%s]}",
                    calls, calls == 0 ? 0.0 : micros / calls, per));
        }
        System.out.flush();
        if (timedOut && exitOnTimeout) {
            // learned-ai: the cancelled game thread keeps running (the engine does not poll interrupts), so the JVM is
            // no longer trustworthy for further games. Exit with a distinct code; the batch runner restarts the worker.
            System.out.println("SIMNOTE exiting after wall-clock timeout (code 3)");
            System.out.flush();
            System.exit(3);
        }
    }

    private static void simulateTournament(Map<String, List<String>> params, GameRules rules, boolean outputGamelog) {
        String tournament = params.get("t").get(0);
        AbstractTournament tourney = null;
        int matchPlayers = params.containsKey("p") ? Integer.parseInt(params.get("p").get(0)) : 2;

        DeckGroup deckGroup = new DeckGroup("SimulatedTournament");
        List<TournamentPlayer> players = new ArrayList<>();
        int numPlayers = 0;
        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, rules.getGameType(), null);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }

                deckGroup.addAiDeck(d);
                players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), 0), numPlayers));
                numPlayers++;
            }
        }

        if (params.containsKey("D")) {
            // Load decks from the specified directory
            String foldName = params.get("D").get(0);
            File folder = new File(foldName);
            if (!folder.isDirectory()) {
                System.out.println("Directory not found - " + foldName);
            } else {
                for (File deck : folder.listFiles((dir, name) -> name.endsWith(".dck"))) {
                    Deck d = DeckSerializer.fromFile(deck);
                    if (d == null) {
                        System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck.getName(), ", match cannot start"));
                        return;
                    }
                    deckGroup.addAiDeck(d);
                    players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), 0), numPlayers));
                    numPlayers++;
                }
            }
        }

        if (numPlayers == 0) {
            System.out.println("No decks/Players found. Please try again.");
        }

        if ("bracket".equalsIgnoreCase(tournament)) {
            tourney = new TournamentBracket(players, matchPlayers);
        } else if ("roundrobin".equalsIgnoreCase(tournament)) {
            tourney = new TournamentRoundRobin(players, matchPlayers);
        } else if ("swiss".equalsIgnoreCase(tournament)) {
            tourney = new TournamentSwiss(players, matchPlayers);
        }
        if (tourney == null) {
            System.out.println("Failed to initialize tournament, bailing out");
            return;
        }

        tourney.initializeTournament();

        String lastWinner = "";
        int curRound = 0;
        System.out.println(TextUtil.concatNoSpace("Starting a ", tournament, " tournament with ",
                String.valueOf(numPlayers), " players over ",
                String.valueOf(tourney.getTotalRounds()), " rounds"));
        while (!tourney.isTournamentOver()) {
            if (tourney.getActiveRound() != curRound) {
                if (curRound != 0) {
                    System.out.println(TextUtil.concatNoSpace("End Round - ", String.valueOf(curRound)));
                }
                curRound = tourney.getActiveRound();
                System.out.println();
                System.out.println(TextUtil.concatNoSpace("Round ", String.valueOf(curRound), " Pairings:"));

                for (TournamentPairing pairing : tourney.getActivePairings()) {
                    System.out.println(pairing.outputHeader());
                }
                System.out.println();
            }

            TournamentPairing pairing = tourney.getNextPairing();
            List<RegisteredPlayer> regPlayers = AbstractTournament.registerTournamentPlayers(pairing, deckGroup);

            StringBuilder sb = new StringBuilder();
            sb.append("Round ").append(tourney.getActiveRound()).append(" - ");
            sb.append(pairing.outputHeader());
            System.out.println(sb.toString());

            if (!pairing.isBye()) {
                Match mc = new Match(rules, regPlayers, "TourneyMatch");

                int exceptions = 0;
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    // play games until the match ends
                    try {
                        simulateSingleMatch(mc, iGame, outputGamelog);
                        iGame++;
                    } catch (Exception e) {
                        exceptions++;
                        System.out.println(e.toString());
                        if (exceptions > 5) {
                            System.out.println("Exceeded number of exceptions thrown. Abandoning match...");
                            break;
                        } else {
                            System.out.println("Game threw exception. Abandoning game and continuing...");
                        }
                    }

                }
                LobbyPlayer winner = mc.getWinner().getPlayer();
                for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                    if (winner.equals(tp.getPlayer())) {
                        pairing.setWinner(tp);
                        lastWinner = winner.getName();
                        System.out.println(TextUtil.concatNoSpace("Match Winner - ", lastWinner, "!"));
                        System.out.println();
                        break;
                    }
                }
            }

            tourney.reportMatchCompletion(pairing);
        }
        tourney.outputTournamentResults();
    }

    public static Match simulateOffthreadGame(List<Deck> decks, GameType format, int games) {
        return null;
    }

    private static Deck deckFromCommandLineParameter(String deckname, GameType type, String deckDir) {
        int dotpos = deckname.lastIndexOf('.');
        if (dotpos > 0 && dotpos == deckname.length() - 4) {
            String baseDir = deckDir != null ? deckDir : (type.equals(GameType.Commander) ?
                    ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR);

            if (!baseDir.endsWith(File.separator)) {
                baseDir += File.separator;
            }

            File f = new File(baseDir + deckname);
            if (!f.exists()) {
                System.out.println("No deck found in " + baseDir);
            }

            return DeckSerializer.fromFile(f);
        }

        IStorage<Deck> deckStore = null;

        // Add other game types here...
        if (type.equals(GameType.Commander)) {
            deckStore = FModel.getDecks().getCommander();
        } else {
            deckStore = FModel.getDecks().getConstructed();
        }

        return deckStore.get(deckname);
    }

}
