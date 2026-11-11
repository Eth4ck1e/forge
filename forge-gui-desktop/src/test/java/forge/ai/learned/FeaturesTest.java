package forge.ai.learned;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

/** learned-ai M1.1: the feature extractor over hand-built four-player states. */
public class FeaturesTest extends AITest {

    private Game fourPlayerGame() {
        initAndCreateGame();   // loads the card database
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        for (String n : new String[] {"s1", "s2", "s3", "s4"}) {
            players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi(n, null)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    private static int idx(String name) {
        int i = Features.names().indexOf(name);
        AssertJUnit.assertTrue("no feature " + name, i >= 0);
        return i;
    }

    @Test
    public void schemaIsConsistent() {
        AssertJUnit.assertEquals(Features.size(), Features.names().size());
        AssertJUnit.assertEquals(570, Features.size());
        AssertJUnit.assertEquals(9, Features.CANDIDATE.length);
        // schema 5: 17 spell_choice columns appended after the 70 of schema 2-4 (old indices unchanged)
        AssertJUnit.assertEquals(11, Features.SCHEMA);   // 6: combat_attack record fields only (issue #28); 7: combat_snapshot requirement fields; 8: spell_choice candInfo; 9: seq_choice records; 10: combat_attack rules / v11; 11: cast_util records
        AssertJUnit.assertEquals(87, Features.SPELL_CANDIDATE.length);
        AssertJUnit.assertEquals("manaLeft", Features.SPELL_CANDIDATE[31]);
        AssertJUnit.assertEquals("api.other", Features.SPELL_CANDIDATE[69]);
        AssertJUnit.assertEquals("manaUntapped", Features.SPELL_CANDIDATE[70]);
        AssertJUnit.assertEquals("oppCreaturesAffected", Features.SPELL_CANDIDATE[86]);
    }

    @Test
    public void seatsAreRelativeToActingPlayer() {
        Game game = fourPlayerGame();
        List<Player> ps = game.getPlayers();
        Player acting = ps.get(2);
        List<Player> seats = Features.seatsFrom(acting);
        AssertJUnit.assertEquals(4, seats.size());
        AssertJUnit.assertSame(acting, seats.get(0));
        AssertJUnit.assertSame(ps.get(3), seats.get(1));   // acts next
        AssertJUnit.assertSame(ps.get(0), seats.get(2));   // across
        AssertJUnit.assertSame(ps.get(1), seats.get(3));   // acts just before the acting player
        AssertJUnit.assertEquals(3, LearnedHooks.seatNumber(acting));
    }

    @Test
    public void boardAndHandFeatures() {
        Game game = fourPlayerGame();
        List<Player> ps = game.getPlayers();
        Player me = ps.get(0), next = ps.get(1), across = ps.get(2), prev = ps.get(3);
        addCards("Plains", 3, me);
        Card angel = addCard("Serra Angel", me);          // 4/4 flying vigilance
        angel.setSickness(false);
        addCardToZone("Plains", me, ZoneType.Hand);
        addCardToZone("Serra Angel", me, ZoneType.Hand);  // CMC 5
        addCardToZone("Lightning Bolt", me, ZoneType.Hand);  // instant, CMC 1
        addCards("Grizzly Bears", 2, next);                // 2/2 each, summoning sick
        addCard("Mountain", across);
        next.setLife(12, null);
        across.setLife(40, null);
        prev.setLife(33, null);
        addCardToZone("Island", prev, ZoneType.Hand);     // an opponent's hand: only its size may be read

        float[] x = Features.state(me);
        AssertJUnit.assertEquals(Features.size(), x.length);
        AssertJUnit.assertEquals(1f, x[idx("isMyTurn")]);
        AssertJUnit.assertEquals(4f, x[idx("alive")]);
        AssertJUnit.assertEquals(3f, x[idx("s0.lands")]);
        AssertJUnit.assertEquals(1f, x[idx("s0.creatures")]);
        AssertJUnit.assertEquals(4f, x[idx("s0.power")]);
        AssertJUnit.assertEquals(4f, x[idx("s0.evasivePower")]);
        AssertJUnit.assertEquals(1f, x[idx("s0.c0.flying")]);
        AssertJUnit.assertEquals(1f, x[idx("s0.c0.vigilance")]);
        AssertJUnit.assertEquals(0f, x[idx("s0.c0.sick")]);
        AssertJUnit.assertEquals(0f, x[idx("s0.c1.present")]);
        AssertJUnit.assertEquals(3f, x[idx("s0.hand")]);
        AssertJUnit.assertEquals(1f, x[idx("handCreatures")]);
        AssertJUnit.assertEquals(1f, x[idx("handInstants")]);
        AssertJUnit.assertEquals(1f, x[idx("handLands")]);
        AssertJUnit.assertEquals(3f, x[idx("handMeanCmc")]);
        AssertJUnit.assertEquals(1f, x[idx("handMinCmc")]);
        // next opponent
        AssertJUnit.assertEquals(12f, x[idx("s1.life")]);
        AssertJUnit.assertEquals(12f / next.getStartingLife(), x[idx("s1.lifeFrac")], 1e-6);   // 20 life in a Constructed test game
        AssertJUnit.assertEquals(2f, x[idx("s1.creatures")]);
        AssertJUnit.assertEquals(4f, x[idx("s1.power")]);
        AssertJUnit.assertEquals(0f, x[idx("s1.evasivePower")]);
        AssertJUnit.assertEquals(1f, x[idx("s1.c0.sick")]);
        AssertJUnit.assertEquals(1f, x[idx("s1.c1.present")]);
        AssertJUnit.assertEquals(0f, x[idx("s1.c2.present")]);
        // across and previous
        AssertJUnit.assertEquals(1f, x[idx("s2.lands")]);
        AssertJUnit.assertEquals(33f, x[idx("s3.life")]);
        AssertJUnit.assertEquals(1f, x[idx("s3.hand")]);

        // candidates for the attack target: positions and life ranks
        List<Player> seats = Features.seatsFrom(me);
        float[] cNext = Features.attackCandidate(me, next, seats, 50, false);
        float[] cAcross = Features.attackCandidate(me, across, seats, 70, true);
        float[] cPrev = Features.attackCandidate(me, prev, seats, 60, false);
        int isNext = 1, isAcross = 2, isPrev = 3, lifeRank = 4, lowest = 5, highest = 6, threat = 7, pick = 8;
        AssertJUnit.assertEquals(1f, cNext[isNext]); AssertJUnit.assertEquals(0f, cNext[isAcross]); AssertJUnit.assertEquals(0f, cNext[isPrev]);
        AssertJUnit.assertEquals(1f, cAcross[isAcross]);
        AssertJUnit.assertEquals(1f, cPrev[isPrev]);
        AssertJUnit.assertEquals(1f, cNext[lifeRank]); AssertJUnit.assertEquals(1f, cNext[lowest]); AssertJUnit.assertEquals(0f, cNext[highest]);
        AssertJUnit.assertEquals(3f, cAcross[lifeRank]); AssertJUnit.assertEquals(1f, cAcross[highest]);
        AssertJUnit.assertEquals(2f, cPrev[lifeRank]);
        AssertJUnit.assertEquals(70f, cAcross[threat]); AssertJUnit.assertEquals(1f, cAcross[pick]); AssertJUnit.assertEquals(0f, cNext[pick]);
    }

    @Test
    public void eliminatedPlayerLeavesTheSeatOrder() {
        Game game = fourPlayerGame();
        List<Player> ps = game.getPlayers();
        Player me = ps.get(0), gone = ps.get(1), last = ps.get(3);   // capture first: getPlayers() shrinks when a player loses
        gone.setLife(0, null);
        game.getAction().checkGameOverCondition();
        List<Player> seats = Features.seatsFrom(me);
        AssertJUnit.assertEquals(3, seats.size());
        AssertJUnit.assertFalse(seats.contains(gone));
        float[] x = Features.state(me);
        AssertJUnit.assertEquals(3f, x[idx("alive")]);
        AssertJUnit.assertEquals(0f, x[idx("s3.alive")]);   // the empty fourth slot is all zeros
        float[] c = Features.attackCandidate(me, last, seats, 0, false);
        AssertJUnit.assertEquals(1f, c[3]);   // with three left, the player before me is "previous" and not "across"
        AssertJUnit.assertEquals(0f, c[2]);
    }
}
