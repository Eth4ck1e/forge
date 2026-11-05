package forge.ai.learned;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Table;

import forge.card.CardType;
import forge.card.CardTypeView;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardDamageTable;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * learned-ai D19: an exact, compact snapshot of everything the offline combat solver needs, taken when an AI seat has
 * declared its attackers or its blockers. Every seat's life, poison and commander damage; every creature's power,
 * toughness, keywords, tapped / summoning-sick flags, combat-relevant trigger modes and whether it can attack or
 * block right now; planeswalkers with loyalty; and the declaration the stock AI (or the learned pilot) made, so the
 * solver's best line can be compared with what was actually done and with the damage Forge then logged.
 * Written as a {@code "hook":"combat_snapshot"} record in the decision log; only visible information is read.
 *
 * <p>Schema 2, additive fields (2026-11-05; older files lack them, readers must treat them as optional):
 * <ul>
 * <li>Per creature, in both {@code attackers} and {@code blockers} snapshots:
 *   <ul>
 *   <li>{@code col}: colour mask as Forge's MagicColor (W=1, U=2, B=4, R=8, G=16; 0 = colourless).</li>
 *   <li>{@code kf}: keyword-flag bitmask, bit i set when the creature has the keyword now (from any source):
 *       0 flying, 1 reach, 2 first strike, 3 double strike, 4 deathtouch, 5 lifelink, 6 trample (any form),
 *       7 menace, 8 vigilance, 9 defender, 10 hexproof (any form), 11 indestructible, 12 shroud.
 *       {@code kw} (the keyword originals) is kept as before.</li>
 *   <li>{@code pro}: protection bitmask parsed from its Protection keywords: bits 0-4 from white/blue/black/red/green,
 *       5 colorless, 6 monocolored, 7 multicolored, 8 everything, 9 creatures; "each color" sets bits 0-4.</li>
 *   <li>{@code proTyp}: present only when a Protection keyword was not reducible to {@code pro}; the raw
 *       characteristic(s), e.g. "Card.Human", "Player.Opponent" (Forge's "Protection:&lt;valid&gt;:..." form).</li>
 *   <li>{@code trigTxt}: parallel to {@code trig}: the trigger's rules text (Forge's TriggerDescription with the
 *       card name filled in; "" if the trigger has none), capped at 400 characters.</li>
 *   </ul></li>
 * <li>A third moment, written as a separate {@code "hook":"combat_damage"} record (so readers of
 *   {@code combat_snapshot} records are unaffected), from {@link #damage}: once per combat damage step (first-strike
 *   and regular), after ALL combat damage of the step has been assigned (the AI's real split) and before it is dealt,
 *   i.e. after every spell/trigger that resolved between the blockers snapshot and damage. Fields: {@code kind}
 *   "damage", {@code seat} = {@code active} = attacking seat, {@code turn}, {@code fs} (1 = first-strike step),
 *   {@code legacyOrder} (1 if the game uses the pre-2024 damage assignment order rules; 0 = the attacker divides
 *   freely and the order lists are just the block order), {@code lives} [{seat, life, poison}] for every seat in turn
 *   order from the attacker, {@code att} [{id, def, p, t, dmg, kf, ord:[blocker ids in damage assignment order]}] for
 *   every attacker still in combat, {@code blk} [{id, seat, p, t, dmg, kf, ord:[attacker ids in its order]}] for every
 *   blocker, and {@code asg} [{s: source card id, c: target card id | p: target seat, n: amount}] = the combat damage
 *   table exactly as assigned (attacker->blocker, attacker->player/planeswalker, blocker->attacker), BEFORE
 *   prevention / replacement effects. p/t/dmg/kf are the values at that moment (pumps from tricks and triggers
 *   included; dmg includes first-strike damage already dealt in the regular step).</li>
 * </ul>
 * Planeswalkers ({@code pws} per seat) are listed whenever a seat controls one.
 *
 * <p>Schema 2, second additive batch (2026-11-05, fidelity v2 gaps; still {@code "v":2}, all fields optional):
 * <ul>
 * <li>{@code typ} on every creature row of {@code combat_snapshot} and every {@code att}/{@code blk} row of
 *   {@code combat_damage}: supertypes (e.g. "Legendary"), core types other than Creature (e.g. "Artifact"), then
 *   creature types (e.g. "Robot"); a creature with all creature types (changeling) has "*all" instead of the list.</li>
 * <li>{@code prev} / {@code prevTxt}, present only when non-empty, on the same creature rows and on every seat row
 *   ({@code seats} of combat_snapshot, {@code lives} of combat_damage): source card ids and rules texts (parallel,
 *   capped at 300 characters) of the effects in play that may prevent, redirect or replace combat damage dealt to or
 *   by it: replacement effects of type DamageDone / DealtDamage / AssignDealDamage (not marked noncombat-only, zone
 *   and requirement checks passing) and static abilities of the combat-damage modes listed in {@code DAMAGE_STATIC}
 *   (conditions passing), found on any card in the battlefield, command zone (spell effects such as fogs live there),
 *   graveyard, exile or stack. Matching is by the effect's ValidSource / ValidTarget / ValidCard; an effect with none of
 *   them is global and flags every creature and seat. Approximate by design: "this combat is not plain, and why".
 *   Effects generated by Protection keywords are not repeated here (see {@code pro} / {@code proTyp}).</li>
 * <li>A fourth moment, {@code "hook":"combat_result"} ({@code kind} "result"), once per combat that produced at least
 *   one {@code combat_damage} record: written when the end-of-combat step begins (before end-of-combat effects;
 *   {@code at}:"end"), or at game end if the game ended during combat ({@code at}:"gameover"), or when the next combat
 *   starts if the end-of-combat step was skipped by an effect ({@code at}:"late"). Fields: {@code seat} = {@code active}
 *   = attacking seat, {@code turn}, {@code steps} (number of combat damage steps), {@code seats}
 *   [{seat, life, dLife, poison, dPoison, cmdr, lost, cmdrBy?:[{c: commander id, n}]}] for the seats in turn order from
 *   the attacker as at the first damage step, where d* and {@code cmdr} (commander damage received) are the change
 *   from just before the first combat damage step to this moment (so lifelink, damage triggers and anything resolved
 *   in the damage steps are included; attack/block triggers before damage are not), and {@code died}
 *   [{id, tok, seat}] = creatures put into a graveyard from the battlefield since just before the first combat damage
 *   step (any cause, tokens included; seat = controller when it died). For "late" records from a later turn
 *   {@code died} is omitted.</li>
 * </ul>
 */
public final class CombatSnapshot {
    private CombatSnapshot() {}

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') sb.append("\\\\");
            else if (ch == '"') sb.append('\'');
            else if (ch < 0x20) sb.append(' ');   // newlines / tabs in rules text: JSON strings may not hold them raw
            else sb.append(ch);
        }
        sb.append('"');
    }

    private static final Keyword[] FLAG_KW = {Keyword.FLYING, Keyword.REACH, Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE,
            Keyword.DEATHTOUCH, Keyword.LIFELINK, Keyword.TRAMPLE, Keyword.MENACE, Keyword.VIGILANCE, Keyword.DEFENDER,
            Keyword.HEXPROOF, Keyword.INDESTRUCTIBLE, Keyword.SHROUD};

    static int keywordFlags(Card c) {
        int f = 0;
        for (int i = 0; i < FLAG_KW.length; i++) if (c.hasKeyword(FLAG_KW[i])) f |= 1 << i;
        return f;
    }

    /** learned-ai D19 schema 2: supertypes, non-creature core types, creature types ("*all" for changelings). */
    static void types(StringBuilder sb, Card c) {
        CardTypeView t = c.getType();
        sb.append(",\"typ\":[");
        boolean first = true;
        for (CardType.Supertype st : t.getSupertypes()) { if (!first) sb.append(','); first = false; str(sb, st.name()); }
        for (CardType.CoreType ct : t.getCoreTypes()) {
            if (ct == CardType.CoreType.Creature) continue;
            if (!first) sb.append(','); first = false; str(sb, ct.name());
        }
        if (t.hasAllCreatureTypes()) { if (!first) sb.append(','); str(sb, "*all"); }
        else for (String ty : t.getCreatureTypes()) { if (!first) sb.append(','); first = false; str(sb, ty); }
        sb.append(']');
    }

    /** Static-ability modes that change how combat damage is assigned, dealt or prevented. */
    private static final StaticAbilityMode[] DAMAGE_STATIC = {StaticAbilityMode.CombatDamageToughness,
            StaticAbilityMode.CombatDamageNegatePower, StaticAbilityMode.AssignCombatDamageAsUnblocked,
            StaticAbilityMode.AssignNoCombatDamage, StaticAbilityMode.InfectDamage, StaticAbilityMode.WitherDamage,
            StaticAbilityMode.CantPreventDamage, StaticAbilityMode.ColorlessDamageSource};

    /** An effect in play that may prevent / redirect / replace combat damage (see the class javadoc, {@code prev}). */
    static final class Eff {
        final CardTraitBase t; final int host; final String txt; final boolean vs, vt, vc;
        Eff(CardTraitBase t, Card h, String txt) {
            this.t = t; this.host = h.getId(); this.txt = txt.length() > 300 ? txt.substring(0, 300) : txt;
            vs = t.hasParam("ValidSource"); vt = t.hasParam("ValidTarget"); vc = t.hasParam("ValidCard");
        }
        boolean touches(Object o, boolean isCard) {
            if (!vs && !vt && !vc) return true;
            try {
                if (vs && t.matchesValidParam("ValidSource", o)) return true;
                if (vt && t.matchesValidParam("ValidTarget", o)) return true;
                return vc && isCard && t.matchesValidParam("ValidCard", o);
            } catch (RuntimeException e) {
                return true;   // cannot evaluate: flag it, the solver only needs "not plain"
            }
        }
    }

    static List<Eff> damageEffects(Game game) {
        List<Eff> out = new ArrayList<>(4);
        for (Card h : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (ReplacementEffect re : h.getReplacementEffects()) {
                ReplacementType m = re.getMode();
                if (m != ReplacementType.DamageDone && m != ReplacementType.DealtDamage && m != ReplacementType.AssignDealDamage) continue;
                if ("False".equals(re.getParam("IsCombat"))) continue;
                if (re.isKeyword(Keyword.PROTECTION)) continue;   // protection is in pro / proTyp
                try {
                    if (!re.zonesCheck(game.getZoneOf(h)) || !re.requirementsCheck(game)) continue;
                } catch (RuntimeException e) { continue; }
                String d = re.getDescription();
                out.add(new Eff(re, h, d.isEmpty() ? h.getName() + ": " + m.name() : d));
            }
            for (StaticAbility st : h.getStaticAbilities()) {
                StaticAbilityMode hit = null;
                for (StaticAbilityMode m : DAMAGE_STATIC) if (st.checkMode(m)) { hit = m; break; }
                if (hit == null) continue;
                try {
                    if (!st.checkConditions()) continue;
                } catch (RuntimeException e) { continue; }
                String d = st.toString();
                out.add(new Eff(st, h, d == null || d.isEmpty() ? h.getName() + ": " + hit.name() : d));
            }
        }
        return out;
    }

    static void prev(StringBuilder sb, List<Eff> effs, Object o, boolean isCard) {
        if (effs.isEmpty()) return;
        StringBuilder ids = null, txt = null;
        for (Eff e : effs) {
            if (!e.touches(o, isCard)) continue;
            if (ids == null) { ids = new StringBuilder(); txt = new StringBuilder(); }
            else { ids.append(','); txt.append(','); }
            ids.append(e.host); str(txt, e.txt);
        }
        if (ids != null) sb.append(",\"prev\":[").append(ids).append("],\"prevTxt\":[").append(txt).append(']');
    }

    private static final String[] PRO_WORD = {"white", "blue", "black", "red", "green", "colorless"};
    private static final String[] PRO_VALID = {"White", "Blue", "Black", "Red", "Green", "Colorless"};

    /** protection bits (see the class javadoc); characteristics it cannot reduce are added to {@code other} */
    static int protectionBits(String kw, List<String> other) {
        if (kw.startsWith("Protection from ")) {
            String t = kw.substring("Protection from ".length()).trim();
            for (int i = 0; i < PRO_WORD.length; i++) if (t.equals(PRO_WORD[i])) return 1 << i;
            if (t.equals("each color")) return 31;
            if (t.equals("everything")) return 1 << 8;
            if (t.equals("creatures")) return 1 << 9;
            other.add(t);
            return 0;
        }
        if (!kw.startsWith("Protection:")) return 0;
        String[] parts = kw.split(":");
        if (parts.length < 2) return 0;
        int bits = 0;
        for (String v : parts[1].split(",")) {
            String u = v.startsWith("Card.") ? v.substring(5) : v;
            int b = 0;
            for (int i = 0; i < PRO_VALID.length; i++) if (u.equals(PRO_VALID[i])) b = 1 << i;
            if (u.equals("nonColorless")) b = 31;
            else if (u.equals("MonoColor")) b = 1 << 6;
            else if (u.equals("MultiColor")) b = 1 << 7;
            else if (u.equals("Creature")) b = 1 << 9;
            if (b == 0) other.add(v); else bits |= b;
        }
        return bits;
    }

    private static void creature(StringBuilder sb, Player acting, Card c, Combat combat, GameEntity attackDefender, List<Eff> effs) {
        sb.append("{\"id\":").append(c.getId()).append(",\"n\":"); str(sb, c.getName());
        sb.append(",\"p\":").append(c.getNetPower()).append(",\"t\":").append(c.getNetToughness()).append(",\"dmg\":").append(c.getDamage());
        sb.append(",\"cmc\":").append(c.getCMC());
        sb.append(",\"tap\":").append(c.isTapped() ? 1 : 0).append(",\"sick\":").append(c.isSick() ? 1 : 0);
        sb.append(",\"cmd\":").append(c.isCommander() ? 1 : 0).append(",\"tok\":").append(c.isToken() ? 1 : 0);
        sb.append(",\"canAtk\":").append(attackDefender != null && CombatUtil.canAttack(c, attackDefender) ? 1 : 0);
        sb.append(",\"canBlk\":").append(CombatUtil.canBlock(c) ? 1 : 0);
        sb.append(",\"col\":").append(c.getColor().getColor()).append(",\"kf\":").append(keywordFlags(c));
        types(sb, c);
        prev(sb, effs, c, true);
        sb.append(",\"kw\":[");
        boolean first = true;
        int pro = 0;
        List<String> proTyp = null;
        for (KeywordInterface k : c.getKeywords()) {
            String o = k.getOriginal();
            if (!first) sb.append(','); first = false; str(sb, o);
            if (o.startsWith("Protection")) {
                if (proTyp == null) proTyp = new java.util.ArrayList<>(2);
                pro |= protectionBits(o, proTyp);
            }
        }
        sb.append("],\"pro\":").append(pro);
        if (proTyp != null && !proTyp.isEmpty()) {
            sb.append(",\"proTyp\":[");
            for (int i = 0; i < proTyp.size(); i++) { if (i > 0) sb.append(','); str(sb, proTyp.get(i)); }
            sb.append(']');
        }
        sb.append(",\"trig\":[");
        first = true;
        StringBuilder txt = new StringBuilder();
        for (Trigger t : c.getTriggers()) {
            String m = t.getMode() == null ? "?" : t.getMode().name();
            if (!(m.startsWith("Attack") || m.startsWith("Block") || m.startsWith("Damage") || m.startsWith("Combat"))) continue;
            if (!first) { sb.append(','); txt.append(','); } first = false; str(sb, m);
            String d = t.toString();
            str(txt, d.length() > 400 ? d.substring(0, 400) : d);
        }
        sb.append("],\"trigTxt\":[").append(txt).append("]}");
    }

    /**
     * @param kind "attackers" (acting player just declared attackers) or "blockers" (acting player just declared blocks)
     */
    public static String json(Player acting, Combat combat, String kind) {
        List<Player> seats = Features.seatsFrom(acting);
        List<Eff> effs = damageEffects(acting.getGame());
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
            prev(sb, effs, p, false);
            sb.append(",\"untappedLands\":");
            int ul = 0;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) if (c.isLand() && c.isUntapped()) ul++;
            sb.append(ul);
            // a creature of the acting player "canAtk" is judged against the first opponent in turn order; of an opponent, against the acting player
            GameEntity def = p == acting ? (seats.size() > 1 ? seats.get(1) : null) : acting;
            sb.append(",\"creatures\":[");
            boolean first = true;
            for (Card c : p.getCreaturesInPlay()) {
                if (!first) sb.append(','); first = false; creature(sb, acting, c, combat, def, effs);
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

    private static void defender(StringBuilder sb, GameEntity d) {
        if (d instanceof Player) sb.append(LearnedHooks.seatNumber((Player) d));
        else if (d instanceof Card) sb.append("\"pw:").append(((Card) d).getId()).append('"');
        else sb.append("null");
    }

    private static void ids(StringBuilder sb, CardCollection cc) {
        sb.append('[');
        if (cc != null) for (int i = 0; i < cc.size(); i++) { if (i > 0) sb.append(','); sb.append(cc.get(i).getId()); }
        sb.append(']');
    }

    private static void combatant(StringBuilder sb, Card c, List<Eff> effs) {
        sb.append("{\"id\":").append(c.getId()).append(",\"p\":").append(c.getNetPower()).append(",\"t\":").append(c.getNetToughness())
          .append(",\"dmg\":").append(c.getDamage()).append(",\"kf\":").append(keywordFlags(c));
        types(sb, c);
        prev(sb, effs, c, true);
    }

    /** Installed as {@code Combat.learnedDamageObserver} while the decision log is open (see {@link LearnLog#open}). */
    public static void damage(Combat combat, Boolean firstStrike) {
        try {
            Player atk = combat.getAttackingPlayer();
            Game game = atk.getGame();
            if (game.isSimulationCopy() || !LearnLog.enabled()) return;
            resultBaseline(combat);   // before the record, so a "late" result of an earlier combat is written first
            LearnLog.raw("combat_damage", damageJson(combat, firstStrike));
        } catch (RuntimeException e) {
            System.err.println("learnlog combat_damage: " + e);   // never let logging disturb the game
        }
    }

    static String damageJson(Combat combat, boolean firstStrike) {
        Player atk = combat.getAttackingPlayer();
        List<Eff> effs = damageEffects(atk.getGame());
        StringBuilder sb = new StringBuilder(1024);
        int seat = LearnedHooks.seatNumber(atk);
        sb.append("\"kind\":\"damage\",\"seat\":").append(seat);
        sb.append(",\"turn\":").append(atk.getGame().getPhaseHandler().getTurn()).append(",\"active\":")
          .append(LearnedHooks.seatNumber(atk.getGame().getPhaseHandler().getPlayerTurn()));
        sb.append(",\"fs\":").append(firstStrike ? 1 : 0).append(",\"legacyOrder\":").append(combat.isLegacyOrderCombatants() ? 1 : 0);
        sb.append(",\"lives\":[");
        List<Player> seats = Features.seatsFrom(atk);
        for (int i = 0; i < seats.size(); i++) {
            Player p = seats.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"seat\":").append(LearnedHooks.seatNumber(p)).append(",\"life\":").append(p.getLife()).append(",\"poison\":").append(p.getPoisonCounters());
            prev(sb, effs, p, false);
            sb.append('}');
        }
        sb.append("],\"att\":[");
        boolean first = true;
        for (Card a : combat.getAttackers()) {
            if (!first) sb.append(','); first = false;
            combatant(sb, a, effs);
            sb.append(",\"def\":"); defender(sb, combat.getDefenderByAttacker(a));
            CardCollection ord = combat.getBlockersInDamageOrder(a);
            sb.append(",\"ord\":"); ids(sb, ord != null ? ord : combat.getBlockers(a));
            sb.append('}');
        }
        sb.append("],\"blk\":[");
        first = true;
        for (Card b : combat.getAllBlockers()) {
            if (!first) sb.append(','); first = false;
            combatant(sb, b, effs);
            sb.append(",\"seat\":").append(LearnedHooks.seatNumber(b.getController()));
            CardCollection ord = combat.getAttackersInDamageOrder(b);
            sb.append(",\"ord\":"); ids(sb, ord != null ? ord : combat.getAttackersBlockedBy(b));
            sb.append('}');
        }
        sb.append("],\"asg\":[");
        first = true;
        CardDamageTable t = combat.getAssignedCombatDamage();
        for (Table.Cell<Card, GameEntity, Integer> cell : t.cellSet()) {
            if (cell.getRowKey() == null || cell.getValue() == null) continue;
            if (!first) sb.append(','); first = false;
            sb.append("{\"s\":").append(cell.getRowKey().getId());
            GameEntity to = cell.getColumnKey();
            if (to instanceof Player) sb.append(",\"p\":").append(LearnedHooks.seatNumber((Player) to));
            else if (to instanceof Card) sb.append(",\"c\":").append(((Card) to).getId());
            sb.append(",\"n\":").append(cell.getValue()).append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    // ---- combat_result (schema 2): state captured just before the first combat damage step of the current combat ----
    private static Combat pCombat;
    private static Game pGame;
    private static int pTurn, pSteps;
    private static List<Player> pSeats;
    private static int[] pLife, pPoison, pCmdr;
    private static List<Player> pGravePlayers;
    private static int[] pGrave;   // per registered player: size of graveyard "entered from battlefield this turn"

    private static int cmdrTotal(Player p) {
        int n = 0;
        for (Map.Entry<Card, Integer> e : p.getCommanderDamage()) n += e.getValue() == null ? 0 : e.getValue();
        return n;
    }

    private static synchronized void resultBaseline(Combat combat) {
        if (pCombat != null && pCombat != combat) flushPending("late");
        if (pCombat == combat) { pSteps++; return; }
        Player atk = combat.getAttackingPlayer();
        Game game = atk.getGame();
        pCombat = combat; pGame = game; pTurn = game.getPhaseHandler().getTurn(); pSteps = 1;
        pSeats = Features.seatsFrom(atk);
        int n = pSeats.size();
        pLife = new int[n]; pPoison = new int[n]; pCmdr = new int[n];
        for (int i = 0; i < n; i++) {
            Player p = pSeats.get(i);
            pLife[i] = p.getLife(); pPoison[i] = p.getPoisonCounters(); pCmdr[i] = cmdrTotal(p);
        }
        pGravePlayers = new ArrayList<>(game.getRegisteredPlayers());
        pGrave = new int[pGravePlayers.size()];
        for (int i = 0; i < pGrave.length; i++) pGrave[i] = pGravePlayers.get(i).getZone(ZoneType.Graveyard).getCardsAddedThisTurn(ZoneType.Battlefield).size();
        // per-commander baseline for cmdrBy
        pCmdrBy = new java.util.IdentityHashMap<>();
        for (Player p : pSeats) {
            Map<Card, Integer> m = new java.util.HashMap<>();
            for (Map.Entry<Card, Integer> e : p.getCommanderDamage()) m.put(e.getKey(), e.getValue());
            pCmdrBy.put(p, m);
        }
    }
    private static Map<Player, Map<Card, Integer>> pCmdrBy;

    /** Installed as {@code Combat.learnedEndObserver} while the decision log is open: the end-of-combat step begins. */
    public static void combatEnd(Combat combat) {
        try {
            synchronized (CombatSnapshot.class) {
                if (pCombat != combat) return;   // no combat damage was dealt in this combat (or a simulation copy)
                flushPending("end");
            }
        } catch (RuntimeException e) {
            System.err.println("learnlog combat_result: " + e);
        }
    }

    /** Writes the pending combat_result, if any, and clears it. {@code at}: "end", "gameover" or "late". */
    static synchronized void flushPending(String at) {
        if (pCombat == null) return;
        try {
            if (LearnLog.enabled()) LearnLog.raw("combat_result", resultJson(at));
        } catch (RuntimeException e) {
            System.err.println("learnlog combat_result: " + e);
        } finally {
            pCombat = null; pGame = null; pSeats = null; pGravePlayers = null; pCmdrBy = null;
        }
    }

    /** Drops the pending result without writing it (a new game started). */
    static synchronized void resetPending() {
        pCombat = null; pGame = null; pSeats = null; pGravePlayers = null; pCmdrBy = null;
    }

    private static String resultJson(String at) {
        Player atk = pCombat.getAttackingPlayer();
        int turn = pGame.getPhaseHandler().getTurn();
        StringBuilder sb = new StringBuilder(512);
        sb.append("\"kind\":\"result\",\"seat\":").append(LearnedHooks.seatNumber(atk)).append(",\"turn\":").append(pTurn)
          .append(",\"active\":").append(LearnedHooks.seatNumber(atk)).append(",\"at\":\"").append(at).append('"')
          .append(",\"steps\":").append(pSteps).append(",\"seats\":[");
        for (int i = 0; i < pSeats.size(); i++) {
            Player p = pSeats.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"seat\":").append(LearnedHooks.seatNumber(p)).append(",\"life\":").append(p.getLife())
              .append(",\"dLife\":").append(p.getLife() - pLife[i]).append(",\"poison\":").append(p.getPoisonCounters())
              .append(",\"dPoison\":").append(p.getPoisonCounters() - pPoison[i]).append(",\"cmdr\":").append(cmdrTotal(p) - pCmdr[i])
              .append(",\"lost\":").append(p.hasLost() ? 1 : 0);
            Map<Card, Integer> base = pCmdrBy.get(p);
            boolean first = true;
            for (Map.Entry<Card, Integer> e : p.getCommanderDamage()) {
                Integer b0 = base == null ? null : base.get(e.getKey());
                int d = (e.getValue() == null ? 0 : e.getValue()) - (b0 == null ? 0 : b0);
                if (d == 0) continue;
                sb.append(first ? ",\"cmdrBy\":[" : ",");
                first = false;
                sb.append("{\"c\":").append(e.getKey().getId()).append(",\"n\":").append(d).append('}');
            }
            if (!first) sb.append(']');
            sb.append('}');
        }
        sb.append(']');
        if (turn == pTurn) {
            sb.append(",\"died\":[");
            boolean first = true;
            for (int i = 0; i < pGravePlayers.size(); i++) {
                List<Card> added = pGravePlayers.get(i).getZone(ZoneType.Graveyard).getCardsAddedThisTurn(ZoneType.Battlefield);
                for (int k = pGrave[i]; k < added.size(); k++) {
                    Card c = added.get(k);
                    if (!c.isCreature()) continue;
                    if (!first) sb.append(','); first = false;
                    sb.append("{\"id\":").append(c.getId()).append(",\"tok\":").append(c.isToken() ? 1 : 0)
                      .append(",\"seat\":").append(c.getController() == null ? 0 : LearnedHooks.seatNumber(c.getController())).append('}');
                }
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
