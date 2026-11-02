package forge.ai.learned;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * learned-ai M1 feature extractor, v0 (plan section M1 "Feature set v0").
 * <p>
 * Serialises what the acting player can see into a fixed-size float vector. Seats are ordered relative to the acting
 * player in turn order (self, then the player who acts next, and so on), so "the opponent who acts right after me"
 * is always the same slot. Only visible information is read: for every other player the hand and library contribute
 * their <em>size</em> only, never their contents, and no library is read in any order (see {@code eval/leakage_check.py}).
 * <p>
 * Card identity enters only through types, keywords and costs in v0; a role map is a later schema version.
 */
public final class Features {
    public static final int SCHEMA = 1;
    public static final int SEATS = 4;
    /** Creature slots per seat, best first by power. */
    public static final int CREATURE_SLOTS = 6;

    private static final String[] GAME = {"turn", "roundApprox", "phase", "isMyTurn", "activeRel", "alive", "nPlayers"};
    private static final String[] SEAT = {"alive", "life", "lifeFrac", "poison", "library", "hand", "lands", "untappedLands", "manaEst",
            "creatures", "untappedCreatures", "power", "toughness", "evasivePower", "otherPermanents", "planeswalkers", "graveyard",
            "commandersOnBattlefield", "commandersInCommandZone", "commanderCasts", "monarch", "initiative", "lifeLostThisTurn",
            "cmdDmgFromMe", "cmdDmgToMe"};
    private static final String[] CREATURE = {"present", "power", "toughness", "cmc", "tapped", "sick", "flying", "menace", "trample",
            "deathtouch", "lifelink", "firstStrike", "vigilance", "hexproof", "indestructible", "reach", "defender", "commander", "token"};
    private static final String[] HAND = {"handCreatures", "handInstants", "handSorceries", "handLands", "handOther", "handMeanCmc", "handMinCmc"};
    public static final String[] CANDIDATE = {"relSeat", "isNext", "isAcross", "isPrevious", "lifeRank", "isLowestLife", "isHighestLife",
            "stockThreat", "stockPick"};

    private static final Keyword[] EVASION = {Keyword.FLYING, Keyword.MENACE, Keyword.TRAMPLE};

    private Features() {}

    /** Feature names in vector order, for the schema line of a decision log. */
    public static List<String> names() {
        List<String> n = new ArrayList<>();
        Collections.addAll(n, GAME);
        for (int s = 0; s < SEATS; s++) {
            for (String f : SEAT) n.add("s" + s + "." + f);
            for (int c = 0; c < CREATURE_SLOTS; c++) for (String f : CREATURE) n.add("s" + s + ".c" + c + "." + f);
        }
        Collections.addAll(n, HAND);
        return n;
    }

    public static int size() {
        return GAME.length + SEATS * (SEAT.length + CREATURE_SLOTS * CREATURE.length) + HAND.length;
    }

    /** Players still in the game, in turn order starting with {@code acting}. */
    public static List<Player> seatsFrom(Player acting) {
        Game game = acting.getGame();
        List<Player> order = new ArrayList<>(game.getPlayersInTurnOrder());
        int i = order.indexOf(acting);
        if (i < 0) { order.add(0, acting); i = 0; }
        List<Player> out = new ArrayList<>(order.size());
        for (int k = 0; k < order.size(); k++) out.add(order.get((i + k) % order.size()));
        return out;
    }

    public static float[] state(Player acting) {
        Game game = acting.getGame();
        List<Player> seats = seatsFrom(acting);
        float[] x = new float[size()];
        int i = 0;
        int turn = game.getPhaseHandler().getTurn();
        int nReg = Math.max(1, game.getRegisteredPlayers().size());
        Player active = game.getPhaseHandler().getPlayerTurn();
        PhaseType phase = game.getPhaseHandler().getPhase();
        x[i++] = turn;
        x[i++] = (float) turn / nReg;
        x[i++] = phase == null ? 0 : phase.ordinal();
        x[i++] = active == acting ? 1 : 0;
        x[i++] = Math.max(0, seats.indexOf(active));
        x[i++] = seats.size();
        x[i++] = nReg;
        for (int s = 0; s < SEATS; s++) {
            if (s < seats.size()) i = seat(x, i, acting, seats.get(s));
            else i += SEAT.length + CREATURE_SLOTS * CREATURE.length;   // empty seat: zeros
        }
        i = hand(x, i, acting);
        return x;
    }

    private static int seat(float[] x, int i, Player acting, Player p) {
        x[i++] = p.isInGame() && !p.hasLost() ? 1 : 0;
        x[i++] = p.getLife();
        x[i++] = (float) p.getLife() / Math.max(1, p.getStartingLife());
        x[i++] = p.getPoisonCounters();
        x[i++] = p.getZone(ZoneType.Library).size();     // size only
        x[i++] = p.getZone(ZoneType.Hand).size();        // size only
        int lands = 0, untappedLands = 0, creatures = 0, untappedCreatures = 0, power = 0, toughness = 0, evasive = 0, other = 0, pws = 0, cmdOnBf = 0;
        List<Card> cs = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCommander()) cmdOnBf++;
            if (c.isLand()) { lands++; if (c.isUntapped()) untappedLands++; }
            else if (c.isCreature()) {
                creatures++; cs.add(c);
                if (c.isUntapped()) untappedCreatures++;
                int pw = Math.max(0, c.getNetPower()); power += pw; toughness += Math.max(0, c.getNetToughness());
                for (Keyword k : EVASION) if (c.hasKeyword(k)) { evasive += pw; break; }
            } else if (c.isPlaneswalker()) pws++;
            else other++;
        }
        x[i++] = lands; x[i++] = untappedLands;
        x[i++] = ComputerUtilMana.getAvailableManaEstimate(p, false);
        x[i++] = creatures; x[i++] = untappedCreatures; x[i++] = power; x[i++] = toughness; x[i++] = evasive;
        x[i++] = other; x[i++] = pws;
        x[i++] = p.getZone(ZoneType.Graveyard).size();
        int cmdInCz = 0, casts = 0;
        for (Card cmd : p.getCommanders()) {
            if (cmd.getZone() != null && cmd.getZone().getZoneType() == ZoneType.Command) cmdInCz++;
            casts += p.getCommanderCast(cmd);
        }
        x[i++] = cmdOnBf; x[i++] = cmdInCz; x[i++] = casts;
        x[i++] = p.isMonarch() ? 1 : 0;
        x[i++] = p.hasInitiative() ? 1 : 0;
        x[i++] = p.getLifeLostThisTurn();
        int fromMe = 0, toMe = 0;
        for (Card cmd : acting.getCommanders()) fromMe += p.getCommanderDamage(cmd);
        for (Card cmd : p.getCommanders()) toMe += acting.getCommanderDamage(cmd);
        x[i++] = fromMe; x[i++] = toMe;
        cs.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        for (int k = 0; k < CREATURE_SLOTS; k++) {
            if (k < cs.size()) i = creature(x, i, cs.get(k));
            else i += CREATURE.length;
        }
        return i;
    }

    private static int creature(float[] x, int i, Card c) {
        x[i++] = 1;
        x[i++] = c.getNetPower(); x[i++] = c.getNetToughness(); x[i++] = c.getCMC();
        x[i++] = c.isTapped() ? 1 : 0; x[i++] = c.isSick() ? 1 : 0;
        x[i++] = kw(c, Keyword.FLYING); x[i++] = kw(c, Keyword.MENACE); x[i++] = kw(c, Keyword.TRAMPLE);
        x[i++] = kw(c, Keyword.DEATHTOUCH); x[i++] = kw(c, Keyword.LIFELINK);
        x[i++] = c.hasFirstStrike() || c.hasKeyword(Keyword.DOUBLE_STRIKE) ? 1 : 0;
        x[i++] = kw(c, Keyword.VIGILANCE); x[i++] = kw(c, Keyword.HEXPROOF); x[i++] = kw(c, Keyword.INDESTRUCTIBLE);
        x[i++] = kw(c, Keyword.REACH); x[i++] = kw(c, Keyword.DEFENDER);
        x[i++] = c.isCommander() ? 1 : 0; x[i++] = c.isToken() ? 1 : 0;
        return i;
    }

    private static float kw(Card c, Keyword k) { return c.hasKeyword(k) ? 1 : 0; }

    /** The acting player's own hand, by type and cost. The only hand whose contents are read. */
    private static int hand(float[] x, int i, Player acting) {
        int cr = 0, in = 0, so = 0, la = 0, ot = 0, n = 0, sum = 0, min = 99;
        for (Card c : acting.getCardsIn(ZoneType.Hand)) {
            if (c.isLand()) la++;
            else {
                n++; int cmc = c.getCMC(); sum += cmc; min = Math.min(min, cmc);
                if (c.isCreature()) cr++; else if (c.getType().isInstant()) in++; else if (c.getType().isSorcery()) so++; else ot++;
            }
        }
        x[i++] = cr; x[i++] = in; x[i++] = so; x[i++] = la; x[i++] = ot;
        x[i++] = n == 0 ? 0 : (float) sum / n; x[i++] = n == 0 ? 0 : min;
        return i;
    }

    /**
     * Per-candidate features for the attack-target hook. {@code seats} is {@link #seatsFrom(Player)} for the acting player.
     */
    public static float[] attackCandidate(Player acting, Player cand, List<Player> seats, int stockThreat, boolean stockPick) {
        float[] a = new float[CANDIDATE.length];
        int rel = seats.indexOf(cand); int n = seats.size();
        a[0] = rel;
        a[1] = rel == 1 ? 1 : 0;
        a[2] = n > 3 && rel == 2 ? 1 : 0;
        a[3] = n > 2 && rel == n - 1 ? 1 : 0;
        List<Player> opps = new ArrayList<>(seats); opps.remove(acting);
        int lower = 0, hi = Integer.MIN_VALUE, lo = Integer.MAX_VALUE;
        for (Player o : opps) { if (o.getLife() < cand.getLife()) lower++; hi = Math.max(hi, o.getLife()); lo = Math.min(lo, o.getLife()); }
        a[4] = lower + 1;
        a[5] = cand.getLife() == lo ? 1 : 0;
        a[6] = cand.getLife() == hi ? 1 : 0;
        a[7] = stockThreat;
        a[8] = stockPick ? 1 : 0;
        return a;
    }
}
