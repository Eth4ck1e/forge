package forge.gamemodes.match;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import forge.ai.learned.SeatProfiles;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckType;
import forge.deck.io.DeckSerializer;
import forge.localinstance.properties.ForgeConstants;

/**
 * learned-ai GUI play: {@code -Dlearned.seatDecks=2:mirror-scd-token,3:Draconic Destruction} preassigns a lobby slot's
 * Commander precon deck. The desktop lobby (VLobby.createDeckChooser) writes the returned chooser state into the slot's
 * saved deck state once per launch, so the slot's deck chooser opens on that precon and the user can still change it.
 * Unknown entries log {@code SIMNOTE learned seatDecks seat=N unknown deck ...} and leave the slot alone.
 */
public final class LearnedSeatDecks {
    private LearnedSeatDecks() {}

    private static final Set<Integer> APPLIED = new HashSet<>();

    /** FDeckChooser saved-state string ("PRECON_COMMANDER_DECK;<deck>") for this seat, once per launch; else null */
    public static synchronized String chooserStateFor(int seat) {
        String spec = SeatProfiles.deckSpecFor(seat);
        if (spec == null || APPLIED.contains(seat)) return null;
        APPLIED.add(seat);
        List<String> shown = new ArrayList<>();
        for (DeckProxy p : DeckProxy.getAllCommanderPreconDecks()) shown.add(p.toString());
        String hit = match(spec, shown, LearnedSeatDecks::nameOfStem);
        if (hit == null) {
            System.out.println("SIMNOTE learned seatDecks seat=" + seat + " unknown deck " + spec
                    + " (no Commander precon with that file stem or name; slot left alone)");
            return null;
        }
        System.out.println("SIMNOTE learned seatDecks seat=" + seat + " deck=" + hit);
        return DeckType.PRECON_COMMANDER_DECK.name() + ";" + hit;
    }

    /**
     * The chooser string (as shown: "path/name" or "name") for a spec: exact shown string, exact deck name, the name of
     * the deck in file {@code <spec>.dck}, then a case-insensitive name match. Null when nothing matches.
     */
    public static String match(String spec, List<String> shown, Function<String, String> stemToName) {
        String s = spec.trim();
        if (s.endsWith(".dck")) s = s.substring(0, s.length() - 4);
        for (String x : shown) if (x.equals(s)) return x;
        for (String x : shown) if (leaf(x).equals(s)) return x;
        String byStem = stemToName.apply(s);
        if (byStem != null) for (String x : shown) if (leaf(x).equals(byStem)) return x;
        for (String x : shown) if (leaf(x).equalsIgnoreCase(s)) return x;
        return null;
    }

    private static String leaf(String shown) {
        int i = shown.lastIndexOf('/');
        return i < 0 ? shown : shown.substring(i + 1);
    }

    /** deck name inside {@code COMMANDER_PRECON_DIR/**}/<stem>.dck, or null */
    private static String nameOfStem(String stem) {
        File root = new File(ForgeConstants.COMMANDER_PRECON_DIR);
        if (!root.isDirectory()) return null;
        String want = stem + ".dck";
        try (Stream<Path> files = Files.walk(root.toPath())) {
            Path f = files.filter(p -> p.getFileName().toString().equals(want)).findFirst().orElse(null);
            if (f == null) return null;
            Deck d = DeckSerializer.fromFile(f.toFile());
            return d == null ? null : d.getName();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
