package forge.ai.learned;

import java.util.List;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * learned-ai D19: an exact, compact snapshot of everything the offline combat solver needs, taken when an AI seat has
 * declared its attackers or its blockers. Every seat's life, poison and commander damage; every creature's power,
 * toughness, keywords, tapped / summoning-sick flags, combat-relevant trigger modes and whether it can attack or
 * block right now; planeswalkers with loyalty; and the declaration the stock AI (or the learned pilot) made, so the
 * solver's best line can be compared with what was actually done and with the damage Forge then logged.
 * Written as a {@code "hook":"combat_snapshot"} record in the decision log; only visible information is read.
 */
public final class CombatSnapshot {
    private CombatSnapshot() {}

    private static void str(StringBuilder sb, String s) {
        sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "'")).append('"');
    }

    private static void creature(StringBuilder sb, Player acting, Card c, Combat combat, GameEntity attackDefender) {
        sb.append("{\"id\":").append(c.getId()).append(",\"n\":"); str(sb, c.getName());
        sb.append(",\"p\":").append(c.getNetPower()).append(",\"t\":").append(c.getNetToughness()).append(",\"dmg\":").append(c.getDamage());
        sb.append(",\"cmc\":").append(c.getCMC());
        sb.append(",\"tap\":").append(c.isTapped() ? 1 : 0).append(",\"sick\":").append(c.isSick() ? 1 : 0);
        sb.append(",\"cmd\":").append(c.isCommander() ? 1 : 0).append(",\"tok\":").append(c.isToken() ? 1 : 0);
        sb.append(",\"canAtk\":").append(attackDefender != null && CombatUtil.canAttack(c, attackDefender) ? 1 : 0);
        sb.append(",\"canBlk\":").append(CombatUtil.canBlock(c) ? 1 : 0);
        sb.append(",\"kw\":[");
        boolean first = true;
        for (KeywordInterface k : c.getKeywords()) {
            if (!first) sb.append(','); first = false; str(sb, k.getOriginal());
        }
        sb.append("],\"trig\":[");
        first = true;
        for (Trigger t : c.getTriggers()) {
            String m = t.getMode() == null ? "?" : t.getMode().name();
            if (!(m.startsWith("Attack") || m.startsWith("Block") || m.startsWith("Damage") || m.startsWith("Combat"))) continue;
            if (!first) sb.append(','); first = false; str(sb, m);
        }
        sb.append("]}");
    }

    /**
     * @param kind "attackers" (acting player just declared attackers) or "blockers" (acting player just declared blocks)
     */
    public static String json(Player acting, Combat combat, String kind) {
        List<Player> seats = Features.seatsFrom(acting);
        StringBuilder sb = new StringBuilder(8192);
        sb.append("\"kind\":\"").append(kind).append("\",\"seat\":").append(LearnedHooks.seatNumber(acting));
        sb.append(",\"turn\":").append(acting.getGame().getPhaseHandler().getTurn());
        sb.append(",\"active\":").append(LearnedHooks.seatNumber(acting.getGame().getPhaseHandler().getPlayerTurn()));
        sb.append(",\"seats\":[");
        for (int i = 0; i < seats.size(); i++) {
            Player p = seats.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"seat\":").append(LearnedHooks.seatNumber(p)).append(",\"life\":").append(p.getLife()).append(",\"poison\":").append(p.getPoisonCounters());
            int cmdFromActing = 0;
            for (Card cmd : acting.getCommanders()) cmdFromActing += p.getCommanderDamage(cmd);
            sb.append(",\"cmdDmgFromActing\":").append(cmdFromActing).append(",\"hand\":").append(p.getZone(ZoneType.Hand).size());
            sb.append(",\"untappedLands\":");
            int ul = 0;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) if (c.isLand() && c.isUntapped()) ul++;
            sb.append(ul);
            // a creature of the acting player "canAtk" is judged against the first opponent in turn order; of an opponent, against the acting player
            GameEntity def = p == acting ? (seats.size() > 1 ? seats.get(1) : null) : acting;
            sb.append(",\"creatures\":[");
            boolean first = true;
            for (Card c : p.getCreaturesInPlay()) {
                if (!first) sb.append(','); first = false; creature(sb, acting, c, combat, def);
            }
            sb.append("],\"pws\":[");
            first = true;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isPlaneswalker()) continue;
                if (!first) sb.append(','); first = false;
                sb.append("{\"id\":").append(c.getId()).append(",\"n\":"); str(sb, c.getName()); sb.append(",\"loy\":").append(c.getCurrentLoyalty()).append('}');
            }
            sb.append("]}");
        }
        sb.append("],\"declared\":[");
        boolean first = true;
        if ("attackers".equals(kind)) {
            for (Card a : combat.getAttackers()) {
                if (a.getController() != acting) continue;
                GameEntity d = combat.getDefenderByAttacker(a);
                if (!first) sb.append(','); first = false;
                sb.append("{\"id\":").append(a.getId()).append(",\"def\":");
                if (d instanceof Player) sb.append(LearnedHooks.seatNumber((Player) d));
                else if (d instanceof Card) sb.append("\"pw:").append(((Card) d).getId()).append('"');
                else sb.append("null");
                sb.append('}');
            }
        } else {
            for (Card a : combat.getAttackers()) {
                if (!first) sb.append(','); first = false;
                sb.append("{\"id\":").append(a.getId()).append(",\"blk\":[");
                boolean f2 = true;
                for (Card b : combat.getBlockers(a)) {
                    if (b.getController() != acting) continue;
                    if (!f2) sb.append(','); f2 = false; sb.append(b.getId());
                }
                sb.append("]}");
            }
        }
        sb.append(']');
        return sb.toString();
    }
}
