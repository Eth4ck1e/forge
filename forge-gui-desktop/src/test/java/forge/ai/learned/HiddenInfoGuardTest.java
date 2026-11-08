package forge.ai.learned;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.Gson;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * learned-ai D39 (audit rec. 1): hidden information never reaches a learned input. Every feature vector and record the
 * acting seat produces (spell_choice x / xa / schema-5 mana-hand fields, attack_target candidates, combat_snapshot,
 * CombatBoard solver input, turn_end) must be identical after the opponents' hand and library contents are exchanged
 * (sizes kept), their libraries reordered, and our own library reordered. A control checks the test is sensitive:
 * exchanging our own hand with our library does change the record. Rec. 2: a face-down creature has no name on the
 * solver board.
 */
public class HiddenInfoGuardTest extends AITest {

    private Game game;
    private Player me;
    private List<Player> opps;
    private SpellAbility bolt, wrath;
    private Card angel, bear, faceDown;

    private void build() {
        initAndCreateGame();   // card database and AI profiles
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        for (String n : new String[] {"s1", "s2", "s3", "s4"}) players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi(n, null)));
        GameRules rules = new GameRules(GameType.Constructed);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        List<Player> ps = game.getRegisteredPlayers();
        me = ps.get(0);
        opps = new ArrayList<>(ps.subList(1, 4));
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        game.getPhaseHandler().onStackResolved();

        addCards("Mountain", 2, me); addCards("Plains", 4, me);
        addCard("Llanowar Elves", me).setSickness(false);
        angel = addCard("Serra Angel", me); angel.setSickness(false);
        Card boltCard = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        Card wrathCard = addCardToZone("Wrath of God", me, ZoneType.Hand);
        addCardToZone("Grizzly Bears", me, ZoneType.Hand);
        addCardToZone("Counterspell", me, ZoneType.Hand);
        for (String n : new String[] {"Forest", "Shock", "Island", "Giant Growth"}) addCardToZone(n, me, ZoneType.Library);

        String[][] hands = {{"Shock", "Island", "Giant Growth"}, {"Counterspell", "Swamp"}, {"Doom Blade", "Forest", "Serra Angel"}};
        String[][] libs = {{"Forest", "Wrath of God", "Llanowar Elves"}, {"Lightning Bolt", "Mountain"}, {"Plains", "Grizzly Bears", "Shock"}};
        for (int k = 0; k < 3; k++) {
            Player o = opps.get(k);
            for (String n : hands[k]) addCardToZone(n, o, ZoneType.Hand);
            for (String n : libs[k]) addCardToZone(n, o, ZoneType.Library);
            addCards("Forest", k + 1, o);
        }
        bear = addCard("Grizzly Bears", opps.get(0)); bear.setSickness(false);
        addCard("Grizzly Bears", opps.get(1));
        faceDown = addCard("Giant Spider", opps.get(2));
        faceDown.turnFaceDown(true);
        faceDown.setSickness(false);

        bolt = boltCard.getFirstSpellAbility(); bolt.setActivatingPlayer(me);
        bolt.getTargets().add(bear);
        wrath = wrathCard.getFirstSpellAbility(); wrath.setActivatingPlayer(me);
    }

    /** every learned input / record of the acting seat, as one comparable string list */
    private List<String> observe() {
        Gson gson = new Gson();
        List<String> out = new ArrayList<>();
        List<Player> seats = Features.seatsFrom(me);
        out.add("state " + Arrays.toString(Features.state(me)));
        Object[] sf = LearnedHooks.spellFeatures(me, Arrays.asList(bolt, wrath), seats, true, true);
        for (float[] a : (float[][]) sf[1]) out.add("spell " + Arrays.toString(a));
        out.add("spellRecord " + sf[2]);
        for (Player o : opps) out.add("attack " + Arrays.toString(Features.attackCandidate(me, o, seats, 3, o == opps.get(0))));
        out.add("turn_end " + LearnedHooks.observing(game, true, () -> ManaView.turnEndJson(me, game.getPhaseHandler().getTurn(), true)));
        Combat combat = new Combat(me);
        combat.addAttacker(angel, opps.get(0));
        out.add("snapshot " + CombatSnapshot.json(me, combat, "attackers"));
        CombatBoard b = CombatBoard.build(me, combat);
        b.input.eligibility = null;
        out.add("board " + gson.toJson(b.input));
        return out;
    }

    private static void exchange(Player p, ZoneType a, ZoneType b) {
        List<Card> ca = new ArrayList<>(p.getCardsIn(a)), cb = new ArrayList<>(p.getCardsIn(b));
        AssertJUnit.assertEquals("sizes must match for an exchange", ca.size(), cb.size());
        p.getZone(a).setCards(cb);
        p.getZone(b).setCards(ca);
    }

    @Test
    public void hiddenZonesDoNotReachLearnedInputs() {
        build();
        List<String> before = observe();
        AssertJUnit.assertEquals(before, observe());   // observing twice changes nothing
        AssertJUnit.assertTrue(before.get(before.size() - 3).contains("\"hand\""));

        for (Player o : opps) {
            exchange(o, ZoneType.Hand, ZoneType.Library);
            List<Card> lib = new ArrayList<>(o.getCardsIn(ZoneType.Library));
            Collections.reverse(lib);
            o.getZone(ZoneType.Library).setCards(lib);
        }
        List<Card> mine = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        Collections.reverse(mine);
        me.getZone(ZoneType.Library).setCards(mine);
        List<String> after = observe();
        for (int i = 0; i < before.size(); i++) AssertJUnit.assertEquals("changed by hidden zones: " + before.get(i).split(" ")[0], before.get(i), after.get(i));

        // control: our own hand is ours to see, so exchanging it with our library must show up
        exchange(me, ZoneType.Hand, ZoneType.Library);
        List<String> own = observe();
        AssertJUnit.assertFalse("own hand change not visible in the spell record", before.get(4).equals(own.get(4)));
    }

    @Test
    public void faceDownCreatureHasNoNameOnTheBoard() {
        build();
        Combat combat = new Combat(me);
        combat.addAttacker(angel, opps.get(0));
        CombatBoard b = CombatBoard.build(me, combat);
        boolean seen = false;
        for (CombatSolver.Seat s : b.input.seats) for (CombatSolver.Creature c : s.creatures) {
            if (c.id != faceDown.getId()) continue;
            seen = true;
            AssertJUnit.assertNull("face-down name leaked: " + c.name, c.name);
            AssertJUnit.assertEquals(2, c.p);
            AssertJUnit.assertEquals(2, c.t);
        }
        AssertJUnit.assertTrue("face-down creature on the board", seen);
        AssertJUnit.assertFalse("face-down name in the snapshot", CombatSnapshot.json(me, combat, "attackers").contains("Giant Spider"));
        AssertJUnit.assertFalse("face-down name on the board", new Gson().toJson(b.input).contains("Giant Spider"));
    }
}
