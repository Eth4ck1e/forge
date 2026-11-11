package forge.ai.learned;

import java.util.LinkedHashMap;
import java.util.Map;

import forge.ai.AiProfileUtil;

/**
 * learned-ai GUI play: per-seat AI profile and deck assignment for the lobby, so one table can mix styles.
 * <ul>
 * <li>{@code -Dlearned.seatProfiles=2:Pressure,3:Default,4:Solver}: preassigns the AI profile of those lobby slots
 * (seat = lobby slot index from 1; slot 1 is usually the human). GameLobby applies it when the slot is created, so the
 * per-slot AI picker shows it and a later pick in the lobby wins.</li>
 * <li>{@code -Dlearned.seatDecks=2:mirror-scd-token,3:Token Triumph}: preassigns the slot's Commander precon deck
 * (deck file stem or the precon's name); resolved in forge-gui (LearnedSeatDecks), parsed here.</li>
 * <li>{@code -Dlearned.aiPicker=false}: hides the per-slot AI picker again (the fork shows it by default; stock shows
 * it only with Preferences "Enable AI picker").</li>
 * </ul>
 * A seat playing a learned style (or listed in seatProfiles) is named "Pressure · Token Triumph [2]" at game start.
 * Absent properties = no effect; headless sims never go through the lobby.
 */
public final class SeatProfiles {
    private SeatProfiles() {}

    /** separator between profile, deck and seat in names and picker labels */
    public static final String SEP = " · ";
    /** picker item meaning "no per-slot override: use Preferences > AI profile" (stock picker defaulted to Default) */
    public static final String PREFS_ITEM = "(Preferences AI profile)";

    /** best-fit decks from the league fit (D36 deck fit); any pool deck works */
    private static final Map<String, String> RECOMMENDED = new LinkedHashMap<>();
    static {
        RECOMMENDED.put("Pressure", "Token Triumph");
        RECOMMENDED.put("Solver", "Draconic Destruction");
        RECOMMENDED.put("Learned", "Forces of the Imperium");
    }

    /** "2:a,3:b" -> {2=a, 3=b}; malformed entries are skipped */
    public static Map<Integer, String> parseSpec(String spec) {
        Map<Integer, String> m = new LinkedHashMap<>();
        if (spec == null || spec.trim().isEmpty()) return m;
        for (String part : spec.split(",")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            String v = part.substring(c + 1).trim();
            if (v.isEmpty()) continue;
            try { m.put(Integer.parseInt(part.substring(0, c).trim()), v); }
            catch (NumberFormatException e) { /* ignored entry */ }
        }
        return m;
    }

    /** the profile override for this seat, or null (property absent, seat not listed, or profile unknown); logs */
    public static String profileFor(int seat) { return profileFor(seat, true); }

    public static String profileFor(int seat, boolean log) {
        String p = parseSpec(System.getProperty("learned.seatProfiles")).get(seat);
        if (p == null) return null;
        if (!AiProfileUtil.getAvailableProfiles().contains(p)) {
            if (log) System.out.println("SIMNOTE learned seatProfiles seat=" + seat + " unknown profile " + p + " (ignored; known: " + AiProfileUtil.getAvailableProfiles() + ")");
            return null;
        }
        if (log) System.out.println("SIMNOTE learned seatProfiles seat=" + seat + " profile=" + p);
        return p;
    }

    /** the raw learned.seatDecks entry for this seat (file stem or precon name), or null */
    public static String deckSpecFor(int seat) {
        return parseSpec(System.getProperty("learned.seatDecks")).get(seat);
    }

    /** the fork shows the per-slot AI picker unless -Dlearned.aiPicker=false; stock preference still enables it */
    public static boolean aiPickerEnabled(boolean stockPref) {
        return stockPref || !"false".equalsIgnoreCase(System.getProperty("learned.aiPicker", "true").trim());
    }

    /** best-fit deck for a learned profile, or null for stock profiles */
    public static String recommendedDeck(String profile) { return profile == null ? null : RECOMMENDED.get(profile); }

    /** profiles that get the "profile · deck [seat]" name at game start */
    public static boolean isLearnedStyle(String profile) { return recommendedDeck(profile) != null; }

    /** picker label: "Pressure · best deck: Token Triumph"; stock profiles unchanged; null/empty -> PREFS_ITEM */
    public static String pickerLabel(String profile) {
        if (profile == null || profile.isEmpty()) return PREFS_ITEM;
        String rec = recommendedDeck(profile);
        return rec == null ? profile : profile + SEP + "best deck: " + rec;
    }

    /** inverse of pickerLabel: the profile name, or "" for PREFS_ITEM (= use the Preferences profile) */
    public static String profileOfLabel(String label) {
        if (label == null || PREFS_ITEM.equals(label)) return "";
        int i = label.indexOf(SEP);
        return i < 0 ? label : label.substring(0, i);
    }

    /** lobby name of a seat: "Pressure · Token Triumph [2]" (deck omitted when unknown; trailing "[SET] [year]" tags dropped) */
    public static String nameFor(String profile, String deckName, int seat) {
        String d = shortDeckName(deckName);
        return profile + (d.isEmpty() ? "" : SEP + d) + " [" + seat + "]";
    }

    /** "Token Triumph [SCD] [2022]" -> "Token Triumph"; a name that is only tags is kept */
    public static String shortDeckName(String deckName) {
        if (deckName == null) return "";
        String d = deckName.trim();
        String s = d.replaceAll("(\\s*\\[[^\\]]*\\])+$", "").trim();
        return s.isEmpty() ? d : s;
    }

    /** lobby name of an overridden seat without a deck (kept for callers of the 310edacf06b API) */
    public static String nameFor(String profile, int seat) { return nameFor(profile, null, seat); }
}
