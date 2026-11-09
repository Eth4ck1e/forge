package forge.ai.learned;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.Gson;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
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
 * learned-ai D33 planner phase 1: at an opponent's declare-attackers step, with that opponent attacking us, the
 * planner enumerates a flash creature and an instant from our hand (and not a sorcery), with legal targets, while the
 * stock AI offers nothing; the enumeration changes no state the stock decision reads; seq_choice records the attack,
 * the stack and the candidates; hidden zones do not reach the record.
 */
public class SequencingPlannerTest extends AITest {

    private Game game;
    private Player me, attacker;
    private List<Player> opps;
    private Card bear;

    private void build() {
        initAndCreateGame();
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        for (String n : new String[] {"s1", "s2", "s3", "s4"}) players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi(n, null)));
        GameRules rules = new GameRules(GameType.Constructed);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        List<Player> ps = game.getRegisteredPlayers();
        me = ps.get(0);
        opps = new ArrayList<>(ps.subList(1, 4));
        attacker = opps.get(0);
        addCards("Island", 3, me); addCards("Forest", 2, me);
        addCard("Grizzly Bears", me).setSickness(false);   // an untapped blocker: stock does not flash in a "valuable blocker"
        addCardToZone("Opt", me, ZoneType.Hand);              // instant (draw: stock waits for main 2 / next-turn EOT)
        addCardToZone("Spectral Sailor", me, ZoneType.Hand);     // flash creature
        addCardToZone("Divination", me, ZoneType.Hand);       // sorcery: never an instant-speed candidate
        addCardToZone("Shock", opps.get(1), ZoneType.Hand);   // another seat's hand
        addCardToZone("Counterspell", opps.get(2), ZoneType.Library);
        bear = addCard("Grizzly Bears", attacker); bear.setSickness(false);
        addCards("Mountain", 2, attacker);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        game.getPhaseHandler().onStackResolved();
        Combat combat = new Combat(attacker);
        combat.addAttacker(bear, me);
        game.getPhaseHandler().setCombat(combat);
    }

    private List<SpellAbility> stockChoice() {
        return ((PlayerControllerAi) me.getController()).getAi().chooseSpellAbilityToPlay();
    }

    private static List<String> names(List<LearnedSequencing.Cand> cs) {
        List<String> n = new ArrayList<>();
        for (LearnedSequencing.Cand c : cs) n.add(c.sa.getHostCard().getName());
        return n;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void flashAndInstantEnumeratedWhenAttacked() {
        build();
        List<SpellAbility> stock = stockChoice();
        AssertJUnit.assertTrue("stock offers a play here: " + stock, stock == null || stock.isEmpty());

        List<LearnedSequencing.Cand> cands = LearnedHooks.observing(me, true, () -> LearnedSequencing.enumerate(me));
        List<String> n = names(cands);
        AssertJUnit.assertTrue(n.toString(), n.contains("Opt"));
        AssertJUnit.assertTrue(n.toString(), n.contains("Spectral Sailor"));
        AssertJUnit.assertFalse(n.toString(), n.contains("Divination"));
        AssertJUnit.assertFalse(n.toString(), n.contains("Shock"));

        // observation leaves the stock decision as it was
        List<SpellAbility> again = stockChoice();
        AssertJUnit.assertTrue("stock changed after enumeration: " + again, again == null || again.isEmpty());

        LearnedSequencing.resetGame();
        LearnedSequencing.Pre pre = LearnedHooks.observing(me, true, () -> LearnedSequencing.pre(me));
        String body = LearnedHooks.observing(me, true, () -> LearnedSequencing.post(me, pre, Collections.emptyList(), null, 0));
        AssertJUnit.assertNotNull(body);
        Map<String, Object> r = new Gson().fromJson("{" + body + "}", Map.class);
        AssertJUnit.assertEquals("COMBAT_DECLARE_ATTACKERS", r.get("step"));
        AssertJUnit.assertEquals(2.0, r.get("active"));
        AssertJUnit.assertEquals(0.0, r.get("myTurn"));
        Map<String, Object> att = (Map<String, Object>) r.get("attacked");
        AssertJUnit.assertEquals(1.0, att.get("n"));
        AssertJUnit.assertEquals(Collections.singletonList(2.0), att.get("by"));
        AssertJUnit.assertEquals(Collections.singletonList("Grizzly Bears"), att.get("names"));
        List<Map<String, Object>> sc = (List<Map<String, Object>>) r.get("seqCands");
        AssertJUnit.assertEquals(2, sc.size());
        for (Map<String, Object> c : sc) {
            if ("Spectral Sailor".equals(c.get("card"))) { AssertJUnit.assertEquals(1.0, c.get("flash")); AssertJUnit.assertEquals("permanent", c.get("kind")); }
            if ("Opt".equals(c.get("card"))) { AssertJUnit.assertEquals("U", c.get("col")); AssertJUnit.assertEquals("Hand", c.get("zone")); }
        }
        AssertJUnit.assertEquals(-1.0, r.get("chosen"));
        AssertJUnit.assertTrue(((List<?>) r.get("stockList")).isEmpty());
        AssertJUnit.assertNotNull(r.get("mana"));
        AssertJUnit.assertNull("holdPlan only at own main phases", r.get("holdPlan"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void targetsAndHoldPlan() {
        build();
        addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        addCards("Mountain", 1, me);
        List<LearnedSequencing.Cand> cands = LearnedHooks.observing(me, true, () -> LearnedSequencing.enumerate(me));
        Map<String, Object> bolt = null;
        for (LearnedSequencing.Cand c : cands) if (c.sa.getHostCard().getName().equals("Lightning Bolt")) bolt = new Gson().fromJson(c.json, Map.class);
        AssertJUnit.assertNotNull(names(cands).toString(), bolt);
        List<Map<String, Object>> t = (List<Map<String, Object>>) bolt.get("targets");
        int players = 0; boolean bearSeen = false;
        for (Map<String, Object> e : t) {
            if ("player".equals(e.get("kind"))) players++;
            if (((Number) e.get("id")).intValue() == bear.getId()) bearSeen = true;
        }
        AssertJUnit.assertEquals(4, players);
        AssertJUnit.assertTrue(bearSeen);

        // own main 1: hold plan for {} and each sorcery-speed spell
        game.getPhaseHandler().setCombat(null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        game.getPhaseHandler().onStackResolved();
        LearnedSequencing.Pre pre = LearnedHooks.observing(me, true, () -> LearnedSequencing.pre(me));
        Map<String, Object> r = new Gson().fromJson("{\"x\":0" + pre.context + "}", Map.class);
        Map<String, Object> hp = (Map<String, Object>) r.get("holdPlan");
        AssertJUnit.assertNotNull(pre.context, hp);
        List<Map<String, Object>> ports = (List<Map<String, Object>>) hp.get("portfolios");
        AssertJUnit.assertEquals("{} " + ports, 0, ((List<?>) ports.get(0).get("cast")).size());
        AssertJUnit.assertEquals(3, ((List<?>) ports.get(0).get("hold")).size());   // Opt, Spectral Sailor, Lightning Bolt with all 6 lands open
        boolean div = false;
        for (Map<String, Object> p : ports) {
            if (!((List<?>) p.get("names")).equals(Collections.singletonList("Divination"))) continue;
            div = true;
            AssertJUnit.assertEquals(1.0, p.get("ok"));
            AssertJUnit.assertEquals(3, ((List<?>) p.get("open")).size());   // 6 lands - 3 for Divination
        }
        AssertJUnit.assertTrue(ports.toString(), div);
    }
}
