package forge.ai.learned;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.gamemodes.match.LearnedSeatDecks;

/**
 * learned-ai GUI play: seatProfiles / seatDecks parsing, the per-slot picker labels (best-fit deck hints) and their
 * inverse, the "profile · deck [seat]" names, and seatDecks resolution by shown name, deck name and file stem.
 */
public class SeatProfilesTest {

    @AfterMethod
    public void clear() {
        System.clearProperty("learned.seatDecks");
        System.clearProperty("learned.aiPicker");
    }

    @Test
    public void parsesSpecs() {
        Map<Integer, String> m = SeatProfiles.parseSpec("2:mirror-scd-token, 3:Draconic Destruction,x:bad,4:,5");
        AssertJUnit.assertEquals(2, m.size());
        AssertJUnit.assertEquals("mirror-scd-token", m.get(2));
        AssertJUnit.assertEquals("Draconic Destruction", m.get(3));
        AssertJUnit.assertTrue(SeatProfiles.parseSpec(null).isEmpty());
        System.setProperty("learned.seatDecks", "2:mirror-scd-token,4:mirror-40k-imperium");
        AssertJUnit.assertEquals("mirror-40k-imperium", SeatProfiles.deckSpecFor(4));
        AssertJUnit.assertNull(SeatProfiles.deckSpecFor(3));
    }

    @Test
    public void pickerLabelsRoundTrip() {
        AssertJUnit.assertEquals("Pressure · best deck: Token Triumph", SeatProfiles.pickerLabel("Pressure"));
        AssertJUnit.assertEquals("Solver · best deck: Draconic Destruction", SeatProfiles.pickerLabel("Solver"));
        AssertJUnit.assertEquals("Learned · best deck: Forces of the Imperium", SeatProfiles.pickerLabel("Learned"));
        AssertJUnit.assertEquals("Default", SeatProfiles.pickerLabel("Default"));
        AssertJUnit.assertEquals(SeatProfiles.PREFS_ITEM, SeatProfiles.pickerLabel(""));
        AssertJUnit.assertEquals(SeatProfiles.PREFS_ITEM, SeatProfiles.pickerLabel(null));
        for (String p : Arrays.asList("Pressure", "Solver", "Learned", "Default", "Reckless", ""))
            AssertJUnit.assertEquals(p, SeatProfiles.profileOfLabel(SeatProfiles.pickerLabel(p)));
        AssertJUnit.assertFalse(SeatProfiles.isLearnedStyle("Default"));
        AssertJUnit.assertTrue(SeatProfiles.isLearnedStyle("Solver"));
    }

    @Test
    public void namesCarryTheDeck() {
        AssertJUnit.assertEquals("Pressure · Token Triumph [2]", SeatProfiles.nameFor("Pressure", "Token Triumph", 2));
        AssertJUnit.assertEquals("Learned · Forces of the Imperium [4]",
                SeatProfiles.nameFor("Learned", "Forces of the Imperium [40K] [2022]", 4));
        AssertJUnit.assertEquals("Solver [3]", SeatProfiles.nameFor("Solver", null, 3));
        AssertJUnit.assertEquals("Solver [3]", SeatProfiles.nameFor("Solver", 3));
    }

    @Test
    public void pickerOnByDefault() {
        AssertJUnit.assertTrue(SeatProfiles.aiPickerEnabled(false));
        System.setProperty("learned.aiPicker", "false");
        AssertJUnit.assertFalse(SeatProfiles.aiPickerEnabled(false));
        AssertJUnit.assertTrue(SeatProfiles.aiPickerEnabled(true));
    }

    @Test
    public void resolvesSeatDecks() {
        List<String> shown = Arrays.asList("Token Triumph [SCD] [2022]", "Mirror/Token Triumph (mirror)", "Draconic Destruction");
        java.util.function.Function<String, String> stems = s -> "mirror-scd-token".equals(s) ? "Token Triumph (mirror)" : null;
        AssertJUnit.assertEquals("Mirror/Token Triumph (mirror)", LearnedSeatDecks.match("mirror-scd-token", shown, stems));
        AssertJUnit.assertEquals("Mirror/Token Triumph (mirror)", LearnedSeatDecks.match("mirror-scd-token.dck", shown, stems));
        AssertJUnit.assertEquals("Draconic Destruction", LearnedSeatDecks.match("Draconic Destruction", shown, stems));
        AssertJUnit.assertEquals("Draconic Destruction", LearnedSeatDecks.match("draconic destruction", shown, stems));
        AssertJUnit.assertEquals("Token Triumph [SCD] [2022]", LearnedSeatDecks.match("Token Triumph [SCD] [2022]", shown, stems));
        AssertJUnit.assertNull(LearnedSeatDecks.match("mirror-nope", shown, stems));
    }
}
