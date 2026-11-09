package forge.ai.learned;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * learned-ai schema 8: each spell_choice candidate carries its identity and the acting seat's chosen targets
 * ({@code candInfo}, aligned with {@code candMana} and the feature rows, pass last as null): a card target, a player
 * target, an "and another target" sub-ability, and charm modes with their targets.
 */
public class SpellChoiceTargetsTest extends AITest {

    private Game game;
    private Player me;
    private List<Player> opps;

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
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        game.getPhaseHandler().onStackResolved();
        addCards("Mountain", 4, me); addCards("Swamp", 2, me);
    }

    private SpellAbility spell(String name) {
        SpellAbility sa = addCardToZone(name, me, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        return sa;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cand(Player ai, SpellAbility sa) {
        StringBuilder sb = new StringBuilder();
        ManaView.candJson(sb, ai, sa);
        return new Gson().fromJson(sb.toString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> targets(Map<String, Object> c) { return (List<Map<String, Object>>) c.get("targets"); }

    private static int i(Object o) { return ((Number) o).intValue(); }

    @Test
    public void cardAndPlayerTargetsSerialise() {
        build();
        Card bear = addCard("Grizzly Bears", opps.get(0));
        SpellAbility atCard = spell("Lightning Bolt");
        atCard.getTargets().add(bear);
        SpellAbility atPlayer = spell("Lightning Bolt");
        atPlayer.getTargets().add(opps.get(2));

        Map<String, Object> c = cand(me, atCard);
        AssertJUnit.assertEquals("Lightning Bolt", c.get("card"));
        AssertJUnit.assertEquals(atCard.getHostCard().getId(), i(c.get("cardId")));
        AssertJUnit.assertEquals("DealDamage", c.get("api"));
        List<Map<String, Object>> t = targets(c);
        AssertJUnit.assertEquals(1, t.size());
        AssertJUnit.assertEquals("Grizzly Bears", t.get(0).get("name"));
        AssertJUnit.assertEquals(bear.getId(), i(t.get(0).get("id")));
        AssertJUnit.assertEquals(2, i(t.get(0).get("ctrl")));
        AssertJUnit.assertEquals("card", t.get(0).get("kind"));
        AssertJUnit.assertTrue(((List<?>) c.get("tgtPlayers")).isEmpty());
        AssertJUnit.assertNull(c.get("modes"));

        Map<String, Object> p = cand(me, atPlayer);
        t = targets(p);
        AssertJUnit.assertEquals(1, t.size());
        AssertJUnit.assertEquals("player", t.get(0).get("kind"));
        AssertJUnit.assertEquals(4, i(t.get(0).get("ctrl")));
        AssertJUnit.assertEquals(opps.get(2).getId(), i(t.get(0).get("id")));
        AssertJUnit.assertEquals(opps.get(2).getName(), t.get(0).get("name"));
        AssertJUnit.assertEquals(Arrays.asList(4.0), p.get("tgtPlayers"));

        // the whole record: candInfo aligned with candMana, pass last
        Object[] sf = LearnedHooks.spellFeatures(me, Arrays.asList(atCard, atPlayer), Features.seatsFrom(me), true, true);
        String rec = (String) sf[2];
        AssertJUnit.assertTrue(rec, rec.contains("\"candInfo\":[{\"card\":\"Lightning Bolt\""));
        AssertJUnit.assertTrue(rec, rec.contains("\"tgtPlayers\":[4]},null]"));
    }

    @Test
    public void subAbilityAndCharmTargets() {
        build();
        Card bear = addCard("Grizzly Bears", opps.get(0));
        Card angel = addCard("Serra Angel", opps.get(1));
        SpellAbility arc = spell("Arc Trail");   // 2 damage to any target and 1 damage to another target
        arc.getTargets().add(angel);
        arc.getSubAbility().getTargets().add(opps.get(0));
        Map<String, Object> a = cand(me, arc);
        List<Map<String, Object>> t = targets(a);
        AssertJUnit.assertEquals(2, t.size());
        AssertJUnit.assertEquals("Serra Angel", t.get(0).get("name"));
        AssertJUnit.assertEquals(3, i(t.get(0).get("ctrl")));
        AssertJUnit.assertEquals("player", t.get(1).get("kind"));
        AssertJUnit.assertEquals(Arrays.asList(2.0), a.get("tgtPlayers"));

        SpellAbility cmd = spell("Kolaghan's Command");
        List<AbilitySub> opts = CharmEffect.makePossibleOptions(cmd);
        List<AbilitySub> chosen = new ArrayList<>();
        for (AbilitySub m : opts) {
            String api = m.getApi().name();
            if (api.equals("Discard")) { m.getTargets().add(opps.get(1)); chosen.add(m); }
            if (api.equals("DealDamage")) { m.getTargets().add(bear); chosen.add(m); }
        }
        AssertJUnit.assertEquals(2, chosen.size());
        cmd.setChosenList(chosen);
        CharmEffect.chainAbilities(cmd, chosen);   // as CharmAi does for spells
        Map<String, Object> k = cand(me, cmd);
        AssertJUnit.assertEquals("Charm", k.get("api"));
        t = targets(k);
        AssertJUnit.assertEquals(2, t.size());
        List<String> kinds = new ArrayList<>();
        for (Map<String, Object> e : t) kinds.add(e.get("kind") + ":" + e.get("api"));
        AssertJUnit.assertTrue(kinds.toString(), kinds.contains("player:Discard") && kinds.contains("card:DealDamage"));
        AssertJUnit.assertEquals(Arrays.asList(3.0), k.get("tgtPlayers"));
        AssertJUnit.assertEquals(2, ((List<?>) k.get("modes")).size());
    }
}
