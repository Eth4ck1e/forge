package forge.ai.learned;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.AttackRequirement;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.zone.ZoneType;

/**
 * learned-ai D19/D22 phase 2: {@link CombatSolver.Input} from the live game at the acting seat's attack declaration.
 * Same per-creature fields as an {@code attackers} combat_snapshot (power, toughness, damage, tapped / sick,
 * commander, token, kf, pro, col, keyword text, can attack / block), visible information only. Seats in turn order from
 * the acting seat, living opponents only. Our creature's {@code canAttack} = it can attack at least one opponent
 * player or planeswalker without an attack cost (the snapshot judged it against the first opponent only).
 * Creatures that "can't be blocked" by a static ability get the {@code unblockable} keyword.
 */
public final class CombatBoard {
    public final CombatSolver.Input input = new CombatSolver.Input();
    /** solver ids -> live objects */
    public final Map<Integer, Card> cards = new HashMap<>();
    public final Map<Integer, Player> players = new HashMap<>();
    /** the stock declaration found in combat (attacker -> defender), and the forced part of it */
    public final Map<Card, GameEntity> stock = new LinkedHashMap<>();
    public final Map<Card, GameEntity> forced = new LinkedHashMap<>();
    /** why the solver must not be used on this board ("" = usable) */
    public String exotic = "";

    private CombatBoard() {}

    public static CombatBoard build(Player ai, Combat combat) {
        CombatBoard b = new CombatBoard();
        Game game = ai.getGame();
        b.input.actingSeat = LearnedHooks.seatNumber(ai);
        for (Card a : combat.getAttackers()) if (a.getController() == ai) b.stock.put(a, combat.getDefenderByAttacker(a));
        Map<Card, AttackRequirement> reqs = combat.getAttackConstraints().getRequirements();
        for (Map.Entry<Card, GameEntity> e : b.stock.entrySet()) {
            Card a = e.getKey();
            AttackRequirement r = reqs.get(a);
            if ("True".equals(a.getSVar("MustAttack")) || (r != null && r.hasRequirement()) || a.isGoaded()) b.forced.put(a, e.getValue());
            if (e.getValue() instanceof Card && ((Card) e.getValue()).isBattle()) b.exotic = "battle";
        }
        if (combat.getAttackConstraints().getGlobalRestrictions().getMax() != null
                || !combat.getAttackConstraints().getGlobalRestrictions().getDefenderMax().isEmpty()) b.exotic = "attack-limit";
        List<GameEntity> oppDefenders = new ArrayList<>();
        for (GameEntity d : combat.getDefenders()) {
            Player owner = d instanceof Player ? (Player) d : d instanceof Card ? ((Card) d).getController() : null;
            if (owner != null && owner.isOpponentOf(ai) && !(d instanceof Card && ((Card) d).isBattle())) oppDefenders.add(d);
        }
        for (Player p : Features.seatsFrom(ai)) {
            if (p != ai && (!p.isOpponentOf(ai) || p.hasLost())) continue;
            CombatSolver.Seat s = new CombatSolver.Seat();
            s.seat = LearnedHooks.seatNumber(p); s.life = p.getLife(); s.poison = p.getPoisonCounters();
            for (Card cmd : ai.getCommanders()) s.cmdDmgFromActing += p.getCommanderDamage(cmd);
            b.players.put(s.seat, p);
            for (Card c : p.getCreaturesInPlay()) {
                CombatSolver.Creature cr = creature(c);
                if (p == ai) {
                    boolean can = false;
                    for (GameEntity d : oppDefenders)
                        if (CombatUtil.canAttack(c, d) && CombatUtil.getAttackCost(game, c, d) == null) { can = true; break; }
                    cr.canAttack = can;
                } else {
                    cr.canAttack = CombatUtil.canAttack(c, ai);
                }
                s.creatures.add(cr);
                b.cards.put(c.getId(), c);
            }
            if (p != ai) for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isPlaneswalker()) continue;
                s.pws.add(new CombatSolver.Planeswalker(c.getId(), c.getCurrentLoyalty()));
                b.cards.put(c.getId(), c);
            }
            b.input.seats.add(s);
        }
        for (Map.Entry<Card, GameEntity> e : b.forced.entrySet()) {
            CombatSolver.Target t = b.target(e.getValue());
            if (t != null) b.input.fixed.put(e.getKey().getId(), t);
            else if (b.exotic.isEmpty()) b.exotic = "forced-target";
        }
        return b;
    }

    static CombatSolver.Creature creature(Card c) {
        CombatSolver.Creature cr = new CombatSolver.Creature();
        cr.id = c.getId(); cr.name = c.getName();
        cr.p = c.getNetPower(); cr.t = c.getNetToughness(); cr.dmg = c.getDamage();
        cr.tapped = c.isTapped(); cr.sick = c.isSick(); cr.commander = c.isCommander(); cr.token = c.isToken();
        cr.canBlock = CombatUtil.canBlock(c);
        cr.col = (int) c.getColor().getColor();
        cr.kf = CombatSnapshot.keywordFlags(c);
        int pro = 0;
        List<String> other = new ArrayList<>(1);
        for (KeywordInterface k : c.getKeywords()) {
            String o = k.getOriginal();
            cr.kw.add(o);
            if (o.startsWith("Protection")) pro |= CombatSnapshot.protectionBits(o, other);
        }
        cr.pro = pro;
        if (StaticAbilityCantAttackBlock.cantBlockBy(c, null)) cr.kw.add("Unblockable");
        return cr;
    }

    /** a live defender as a solver target (null for anything else, e.g. a battle or a non-opponent) */
    public CombatSolver.Target target(GameEntity d) {
        if (d instanceof Player) {
            int seat = LearnedHooks.seatNumber((Player) d);
            return players.containsKey(seat) && seat != input.actingSeat ? CombatSolver.Target.player(seat) : null;
        }
        if (d instanceof Card && ((Card) d).isPlaneswalker()) {
            Card w = (Card) d;
            int seat = LearnedHooks.seatNumber(w.getController());
            return players.containsKey(seat) && seat != input.actingSeat ? CombatSolver.Target.planeswalker(seat, w.getId()) : null;
        }
        return null;
    }

    /** a solver target as the live defender */
    public GameEntity defender(CombatSolver.Target t) {
        return t.isPlaneswalker() ? cards.get(t.pwId) : players.get(t.seat);
    }
}
