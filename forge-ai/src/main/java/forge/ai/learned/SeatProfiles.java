package forge.ai.learned;

import java.util.HashMap;
import java.util.Map;

import forge.ai.AiProfileUtil;

/**
 * learned-ai GUI play: per-seat AI profile override for the lobby, so one table can mix styles.
 * {@code -Dlearned.seatProfiles=2:Pressure,3:Default,4:Solver} (seat = lobby slot index from 1; slot 1 is usually the
 * human). Applied by GameLobby when it creates an AI lobby player; the player is then named after its profile
 * ("Pressure [2]"). Absent property = no effect (headless sims never go through the lobby).
 */
public final class SeatProfiles {
    private SeatProfiles() {}

    /** the override for this seat, or null (property absent, seat not listed, or profile unknown) */
    public static String profileFor(int seat) {
        String spec = System.getProperty("learned.seatProfiles");
        if (spec == null || spec.trim().isEmpty()) return null;
        Map<Integer, String> m = new HashMap<>();
        for (String part : spec.split(",")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            try { m.put(Integer.parseInt(part.substring(0, c).trim()), part.substring(c + 1).trim()); }
            catch (NumberFormatException e) { /* ignored entry */ }
        }
        String p = m.get(seat);
        if (p == null || p.isEmpty()) return null;
        if (!AiProfileUtil.getAvailableProfiles().contains(p)) {
            System.out.println("SIMNOTE learned seatProfiles seat=" + seat + " unknown profile " + p + " (ignored; known: " + AiProfileUtil.getAvailableProfiles() + ")");
            return null;
        }
        System.out.println("SIMNOTE learned seatProfiles seat=" + seat + " profile=" + p);
        return p;
    }

    /** lobby name of an overridden seat */
    public static String nameFor(String profile, int seat) { return profile + " [" + seat + "]"; }
}
