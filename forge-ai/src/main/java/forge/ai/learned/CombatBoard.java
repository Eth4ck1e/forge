package forge.ai.learned;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.AttackConstraints;
import forge.game.combat.AttackRequirement;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.staticability.StaticAbilityMustAttack;
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
    /** issue #28: forced attackers the stock declaration left out (fixed from the engine's attack requirements) */
    public final List<Integer> addedForced = new ArrayList<>();
    /** issue #28: player-level requirements as live defender sets (engine form, for the record / reasons) */
    public final List<Collection<GameEntity>> playerReqs = new ArrayList<>();

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
        // issue #28: creatures that must attack ("attacks each combat if able", goad) but are missing from the stock
        // declaration are fixed too, on the defender of the engine's own legal attack (else their strongest requirement)
        Map<Card, GameEntity> legal = null;
        for (Card c : ai.getCreaturesInPlay()) {
            if (b.forced.containsKey(c) || b.stock.containsKey(c)) continue;
            AttackRequirement r = reqs.get(c);
            boolean must = "True".equals(c.getSVar("MustAttack")) || c.isGoaded() || unconditional(r);
            if (!must || c.isTapped() || !CombatUtil.canAttack(c)) continue;
            if (legal == null) legal = combat.getAttackConstraints().getLegalAttackers().getLeft();
            GameEntity d = legal.get(c);
            if (d == null || !oppDefenders.contains(d) || !CombatUtil.canAttack(c, d) || CombatUtil.getAttackCost(game, c, d) != null) {
                d = null;
                int best = -1;
                List<Pair<GameEntity, Integer>> sorted = r == null ? List.of() : r.getSortedRequirements();
                for (Pair<GameEntity, Integer> pr : sorted) {   // ascending by count: the last legal one wins
                    GameEntity x = pr.getLeft();
                    if (!oppDefenders.contains(x) || !CombatUtil.canAttack(c, x) || CombatUtil.getAttackCost(game, c, x) != null) continue;
                    if (pr.getRight() >= best) { best = pr.getRight(); d = x; }
                }
            }
            if (d == null) continue;   // not able: no requirement to honour
            b.forced.put(c, d);
            b.addedForced.add(c.getId());
        }
        // issue #28: per-defender bans (e.g. "can't attack a player it attacked this turn", an attack cost there)
        Set<Integer> fixedLive = new HashSet<>();
        for (Card c : b.forced.keySet()) fixedLive.add(c.getId());
        for (CombatSolver.Creature cr : b.input.seats.get(0).creatures) {
            if (!cr.canAttack || fixedLive.contains(cr.id)) continue;
            Card c = b.cards.get(cr.id);
            Set<CombatSolver.Target> no = new HashSet<>();
            for (GameEntity d : oppDefenders) {
                CombatSolver.Target t = b.target(d);
                if (t != null && (!CombatUtil.canAttack(c, d) || CombatUtil.getAttackCost(game, c, d) != null)) no.add(t);
            }
            if (!no.isEmpty()) b.input.noAttack.put(cr.id, no);
        }
        // issue #28: player-level requirements ("each opponent must attack with at least one creature", Seeker of Slaanesh)
        Multimap<GameEntity, StaticAbility> pr = StaticAbilityMustAttack.mustAttackSpecific(ai, combat.getDefenders());
        if (!pr.isEmpty()) {
            Multimap<StaticAbility, GameEntity> inv = Multimaps.invertFrom(pr, MultimapBuilder.hashKeys().arrayListValues().build());
            for (Collection<GameEntity> defs : inv.asMap().values()) {
                b.playerReqs.add(defs);
                Set<CombatSolver.Target> ts = new HashSet<>();
                for (GameEntity d : defs) { CombatSolver.Target t = b.target(d); if (t != null) ts.add(t); }
                if (!ts.isEmpty()) b.input.mustAttackAny.add(ts);
            }
        }
        for (Map.Entry<Card, GameEntity> e : b.forced.entrySet()) {
            CombatSolver.Target t = b.target(e.getValue());
            if (t != null) b.input.fixed.put(e.getKey().getId(), t);
            else if (b.exotic.isEmpty()) b.exotic = "forced-target";
        }
        return b;
    }

    /** a requirement that holds whatever else attacks (goad, "attacks each combat if able", must attack X) */
    static boolean unconditional(AttackRequirement r) {
        if (r == null) return false;
        for (Pair<GameEntity, Integer> p : r.getSortedRequirements()) if (p.getRight() > 0) return true;
        return false;
    }

    /**
     * issue #28: why the engine rejects this declaration ("" = it accepts it): the same test as
     * {@link CombatUtil#validateAttackers} (violations no more than the engine's best legal attack), read-only, with the
     * violated parts named: {@code restriction}, {@code must-attack:<id>} (a forced creature missing or on the wrong
     * defender), {@code player-must-attack} (no attacker into a player-level requirement).
     */
    public static String rejection(Combat combat, Map<Card, GameEntity> attacks, int best) {
        AttackConstraints cons = combat.getAttackConstraints();
        int v = cons.countViolations(attacks);
        if (v == -1) return "restriction";
        if (v <= best) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Card, AttackRequirement> e : cons.getRequirements().entrySet()) {
            AttackRequirement r = e.getValue();
            if (r == null || !r.hasRequirement() || e.getKey().isTapped() || !CombatUtil.canAttack(e.getKey())) continue;
            if (r.countViolations(attacks.get(e.getKey()), attacks) > 0) { if (sb.length() > 0) sb.append(';'); sb.append("must-attack:").append(e.getKey().getId()); }
        }
        Multimap<GameEntity, StaticAbility> pr = StaticAbilityMustAttack.mustAttackSpecific(combat.getAttackingPlayer(), combat.getDefenders());
        if (!pr.isEmpty()) {
            Multimap<StaticAbility, GameEntity> inv = Multimaps.invertFrom(pr, MultimapBuilder.hashKeys().arrayListValues().build());
            for (Collection<GameEntity> defs : inv.asMap().values())
                if (java.util.Collections.disjoint(defs, attacks.values())) { if (sb.length() > 0) sb.append(';'); sb.append("player-must-attack"); break; }
        }
        if (sb.length() == 0) sb.append("violations:").append(v).append('>').append(best);
        return sb.toString();
    }

    static CombatSolver.Creature creature(Card c) {
        CombatSolver.Creature cr = new CombatSolver.Creature();
        cr.id = c.getId(); cr.name = c.isFaceDown() ? null : c.getName();   // learned-ai D39 rec. 2: a face-down card has no name (null = unknown)
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
