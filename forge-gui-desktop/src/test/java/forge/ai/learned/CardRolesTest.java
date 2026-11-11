package forge.ai.learned;

import java.util.Arrays;
import java.util.Collections;
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

/** learned-ai D45 (schema 12): runtime card roles from abilities, and their record fragments. */
public class CardRolesTest extends AITest {

    private Game game;
    private Player me, opp;

    private void build() {
        initAndCreateGame();
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        for (String n : new String[] {"s1", "s2"}) players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi(n, null)));
        GameRules rules = new GameRules(GameType.Constructed);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        me = game.getRegisteredPlayers().get(0);
        opp = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        game.getPhaseHandler().onStackResolved();
    }

    private List<String> hand(String name) { return CardRoles.of(addCardToZone(name, me, ZoneType.Hand)); }
    private List<String> bf(String name) { return CardRoles.of(addCard(name, me)); }

    @Test
    public void rolesFromAbilities() {
        build();
        AssertJUnit.assertEquals(Arrays.asList("mana"), bf("Llanowar Elves"));
        AssertJUnit.assertEquals(Arrays.asList("mana"), bf("Forest"));
        AssertJUnit.assertEquals(Arrays.asList("removal"), hand("Lightning Bolt"));
        AssertJUnit.assertEquals(Arrays.asList("removal"), hand("Wrath of God"));
        AssertJUnit.assertEquals(Arrays.asList("removal"), hand("Doom Blade"));
        AssertJUnit.assertEquals(Arrays.asList("counter"), hand("Counterspell"));
        AssertJUnit.assertEquals(Arrays.asList("draw"), hand("Divination"));
        AssertJUnit.assertEquals(Arrays.asList("tutor"), hand("Demonic Tutor"));
        AssertJUnit.assertEquals(Collections.emptyList(), hand("Cultivate"));   // land search is not a tutor
        AssertJUnit.assertEquals(Arrays.asList("mkTok"), hand("Raise the Alarm"));
        AssertJUnit.assertEquals(Arrays.asList("dies"), bf("Blood Artist"));
        AssertJUnit.assertEquals(Arrays.asList("etb", "removal"), bf("Ravenous Chupacabra"));
        AssertJUnit.assertEquals(Arrays.asList("sac", "mana"), bf("Ashnod's Altar"));
        AssertJUnit.assertTrue(bf("Goblin Bombardment").contains("sac"));
        AssertJUnit.assertTrue(bf("Young Pyromancer").containsAll(Arrays.asList("cast", "mkTok")));
        AssertJUnit.assertTrue(bf("Hero of Bladehold").containsAll(Arrays.asList("atk", "mkTok")));
        AssertJUnit.assertEquals(Collections.emptyList(), bf("Grizzly Bears"));
        Card spider = addCard("Giant Spider", opp);
        spider.turnFaceDown(true);
        AssertJUnit.assertEquals(Collections.emptyList(), CardRoles.of(spider));   // hidden face: no roles
    }

    @Test
    public void recordFragments() {
        build();
        Card elf = addCard("Llanowar Elves", me), forest = addCard("Forest", me), bears = addCard("Grizzly Bears", me);
        Card artist = addCard("Blood Artist", opp);
        StringBuilder sb = new StringBuilder();
        CardRoles.rolesJson(sb, Arrays.asList(elf, forest, bears, artist, elf));
        AssertJUnit.assertEquals(",\"roles\":{\"" + elf.getId() + "\":[\"mana\"],\"" + artist.getId() + "\":[\"dies\"]}", sb.toString());
        sb.setLength(0);
        CardRoles.roleCountsJson(sb, Arrays.asList(me, opp));
        AssertJUnit.assertEquals(",\"roleCounts\":{\"1\":{\"n\":3,\"mana\":2},\"2\":{\"n\":1,\"dies\":1}}", sb.toString());
        String te = LearnedHooks.observing(me, true, () -> ManaView.turnEndJson(me, 1, true));
        AssertJUnit.assertTrue(te, te.contains("\"handRoles\":[]") && te.contains("\"roleCounts\":{\"1\":"));
    }
}
