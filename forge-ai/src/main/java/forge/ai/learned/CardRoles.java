package forge.ai.learned;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * learned-ai D45 (schema 12): runtime role tags per card, derived from the card's current abilities (no card database).
 * Observation only: reads the card's current state (abilities, triggers, costs, params, SVar text); never builds a
 * trigger's ability (no {@code ensureAbility}), never touches the game.
 * <p>
 * Roles, in this fixed order: {@code etb, atk, dies, cast} (trigger kinds), {@code sac} (sacrifice outlet: an ability
 * whose cost sacrifices something other than itself), {@code mkTok} (makes tokens), {@code tutor} (searches the library
 * for a non-land card), {@code draw}, {@code removal} (targeted destroy, exile or put-into-graveyard from the battlefield,
 * damage to a creature / any target, -X/-X, edict, or a sweeper; bounce is not removal), {@code counter}, {@code mana} (mana ability or mana-making effect),
 * {@code cmdr} (a commander), {@code token} (is a token). Effects count wherever they are: spells, activated abilities
 * and the effects of triggers (an ETB that destroys = etb + removal).
 * <p>
 * Hidden information: a face-down card gets no roles (its face is hidden), callers pass only cards whose name the
 * record shows.
 */
public final class CardRoles {
    private CardRoles() {}

    public static final String[] ORDER = {"etb", "atk", "dies", "cast", "sac", "mkTok", "tutor", "draw", "removal", "counter", "mana", "cmdr", "token"};
    private static final Map<String, Integer> IDX = new HashMap<>();
    static { for (int i = 0; i < ORDER.length; i++) IDX.put(ORDER[i], i); }

    /** roles of a card, in ORDER; empty for null / face-down cards */
    public static List<String> of(Card c) {
        List<String> out = new ArrayList<>();
        if (c == null || c.isFaceDown()) return out;
        boolean[] r = new boolean[ORDER.length];
        try {
            for (Trigger t : c.getTriggers()) {
                TriggerType m = t.getMode();
                if (m == null) continue;
                String o = t.getParamOrDefault("Origin", ""), d = t.getParamOrDefault("Destination", "");
                switch (m) {
                    case ChangesZone: case ChangesZoneAll:
                        if (d.contains("Battlefield")) set(r, "etb");
                        if (o.contains("Battlefield") && d.contains("Graveyard")) set(r, "dies");
                        break;
                    case Attacks: case AttackersDeclared: case AttackersDeclaredOneTarget: case AttackerUnblocked: case AttackerUnblockedOnce:
                        set(r, "atk"); break;
                    case SpellCast: case SpellCastOrCopy: case SpellAbilityCast:
                        set(r, "cast"); break;
                    default: break;
                }
                SpellAbility sa = t.getOverridingAbility();
                if (sa != null) effects(r, sa);
                else if (t.hasParam("Execute")) effectsOfScript(r, c, c.getSVar(t.getParam("Execute")), 0);
            }
            if (!c.getManaAbilities().isEmpty()) set(r, "mana");
            List<SpellAbility> abs = new ArrayList<>(c.getNonManaAbilities());
            abs.addAll(c.getManaAbilities());
            for (SpellAbility sa : abs) {
                if (!sa.isSpell() && !sa.isLandAbility() && sa.getPayCosts() != null) {
                    for (CostPart p : sa.getPayCosts().getCostParts())
                        if (p instanceof CostSacrifice && !p.payCostFromSource()) set(r, "sac");
                }
                effects(r, sa);
            }
        } catch (RuntimeException e) {
            // a card whose traits cannot be read keeps the roles found so far
        }
        if (c.isCommander()) set(r, "cmdr");
        if (c.isToken()) set(r, "token");
        for (int i = 0; i < r.length; i++) if (r[i]) out.add(ORDER[i]);
        return out;
    }

    private static void set(boolean[] r, String role) { r[IDX.get(role)] = true; }

    /** effect roles of an ability and its sub-abilities (and charm choices) */
    private static void effects(boolean[] r, SpellAbility sa) {
        int depth = 0;
        for (SpellAbility s = sa; s != null && depth < 12; s = s.getSubAbility(), depth++) {
            ApiType api = s.getApi();
            if (api == null) continue;
            api(r, api.name(), s.getMapParams(), s.usesTargeting());
            if (api == ApiType.Charm && s.getHostCard() != null && s.hasParam("Choices")) {
                for (String ch : s.getParam("Choices").split(",")) effectsOfScript(r, s.getHostCard(), s.getHostCard().getSVar(ch.trim()), 1);
            }
        }
    }

    /** effect roles of an ability script ("DB$ Token | ... | SubAbility$ X"), following SubAbility SVars */
    private static void effectsOfScript(boolean[] r, Card host, String script, int depth) {
        if (script == null || script.isEmpty() || depth > 8) return;
        Map<String, String> p = parse(script);
        String api = null;
        for (String k : new String[] {"DB", "AB", "SP", "ST"}) if (p.containsKey(k)) { api = p.get(k); break; }
        if (api != null) api(r, api, p, p.containsKey("ValidTgts") || p.containsKey("TgtPrompt"));
        if ("Charm".equals(api) && p.containsKey("Choices"))
            for (String ch : p.get("Choices").split(",")) effectsOfScript(r, host, host.getSVar(ch.trim()), depth + 1);
        if (p.containsKey("SubAbility")) effectsOfScript(r, host, host.getSVar(p.get("SubAbility")), depth + 1);
    }

    /** "K$ v | K2$ v2" -> map (keys without '$') */
    static Map<String, String> parse(String script) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : script.split("\\|")) {
            int i = part.indexOf('$');
            if (i <= 0) continue;
            m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        return m;
    }

    private static String get(Map<String, String> p, String k) { String v = p == null ? null : p.get(k); return v == null ? "" : v; }

    /** roles of one effect by API name and params */
    static void api(boolean[] r, String api, Map<String, String> p, boolean targeted) {
        String valid = get(p, "ValidTgts");
        boolean perm = valid.contains("Creature") || valid.contains("Permanent") || valid.contains("Planeswalker")
                || valid.contains("Artifact") || valid.contains("Enchantment") || valid.contains("Battle") || valid.startsWith("Card");
        String o = get(p, "Origin"), d = get(p, "Destination");
        switch (api) {
            case "Token": case "CopyPermanent": case "Amass": case "Investigate": set(r, "mkTok"); break;
            case "Draw": set(r, "draw"); break;
            case "Counter": set(r, "counter"); break;
            case "Mana": case "ManaReflected": set(r, "mana"); break;
            case "Destroy": if (targeted && perm) set(r, "removal"); break;
            case "DestroyAll": case "DamageAll": case "SacrificeAll": set(r, "removal"); break;
            case "DealDamage":
                if (targeted && (valid.contains("Creature") || valid.contains("Any") || valid.contains("Planeswalker") || valid.contains("Permanent"))) set(r, "removal");
                break;
            case "Pump": if (targeted && perm && get(p, "NumDef").startsWith("-")) set(r, "removal"); break;
            case "Sacrifice":
                if (targeted && (valid.contains("Player") || valid.contains("Opponent")) && (p == null || !p.containsKey("SacValid") || get(p, "SacValid").contains("Creature"))) set(r, "removal");
                break;
            case "ChangeZone": case "ChangeZoneAll":
                if (o.contains("Battlefield") && (d.equals("Exile") || d.equals("Graveyard")) && (perm || "ChangeZoneAll".equals(api))) set(r, "removal");
                if (o.contains("Library") && !d.isEmpty() && !d.equals("Graveyard") && !d.equals("Exile") && p != null && p.containsKey("ChangeType")) {
                    String ct = get(p, "ChangeType");
                    boolean landOnly = ct.startsWith("Land") || ct.contains("Basic") || ct.startsWith("Forest") || ct.startsWith("Island")
                            || ct.startsWith("Swamp") || ct.startsWith("Mountain") || ct.startsWith("Plains");
                    if (!landOnly) set(r, "tutor");
                }
                break;
            default: break;
        }
    }

    // ---- record fragments ---------------------------------------------------------------------------------------------
    /** a land whose only role is mana: left out of the per-card map (it is in the counts) to keep records small */
    private static boolean plainLand(Card c, List<String> roles) {
        return c.isLand() && roles.size() == 1 && "mana".equals(roles.get(0));
    }

    /**
     * {@code ,"roles":{"<cardId>":["etb",...],...}} for the given cards (de-duplicated, first-seen order); cards without
     * roles and plain mana lands are left out. Callers pass only cards the record already shows.
     */
    public static void rolesJson(StringBuilder sb, Collection<Card> cards) {
        sb.append(",\"roles\":{");
        Set<Integer> seen = new LinkedHashSet<>();
        boolean first = true;
        for (Card c : cards) {
            if (c == null || !seen.add(c.getId())) continue;
            List<String> rs = of(c);
            if (rs.isEmpty() || plainLand(c, rs)) continue;
            if (!first) sb.append(','); first = false;
            sb.append('"').append(c.getId()).append("\":");
            ManaView.strs(sb, rs);
        }
        sb.append('}');
    }

    /** the battlefield role counts of one player: {"mana":7,"etb":2,...} (non-zero only, ORDER) plus "n" (permanents) */
    public static void countsJson(StringBuilder sb, Player p) {
        int[] n = new int[ORDER.length];
        int perms = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            perms++;
            for (String r : of(c)) n[IDX.get(r)]++;
        }
        sb.append("{\"n\":").append(perms);
        for (int i = 0; i < n.length; i++) if (n[i] > 0) sb.append(",\"").append(ORDER[i]).append("\":").append(n[i]);
        sb.append('}');
    }

    /** {@code ,"roleCounts":{"<seat>":{...},...}} over the given players (living ones), battlefield only (public) */
    public static void roleCountsJson(StringBuilder sb, Collection<Player> players) {
        sb.append(",\"roleCounts\":{");
        boolean first = true;
        for (Player p : players) {
            if (p == null || p.hasLost()) continue;
            if (!first) sb.append(','); first = false;
            sb.append('"').append(LearnedHooks.seatNumber(p)).append("\":");
            countsJson(sb, p);
        }
        sb.append('}');
    }
}
