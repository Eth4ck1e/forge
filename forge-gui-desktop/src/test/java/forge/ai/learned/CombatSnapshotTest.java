package forge.ai.learned;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/** learned-ai D19: protection keywords reduce to the documented {@code pro} bits; the rest go to {@code proTyp}. */
public class CombatSnapshotTest {
    @Test
    public void protectionBits() {
        List<String> o = new ArrayList<>();
        AssertJUnit.assertEquals(8, CombatSnapshot.protectionBits("Protection from red", o));
        AssertJUnit.assertEquals(31, CombatSnapshot.protectionBits("Protection from each color", o));
        AssertJUnit.assertEquals(256, CombatSnapshot.protectionBits("Protection from everything", o));
        AssertJUnit.assertEquals(32, CombatSnapshot.protectionBits("Protection from colorless", o));
        AssertJUnit.assertEquals(1 | 4, CombatSnapshot.protectionBits("Protection:Card.White,Card.Black:white and from black", o));
        AssertJUnit.assertEquals(512, CombatSnapshot.protectionBits("Protection:Creature:creatures", o));
        AssertJUnit.assertEquals(128, CombatSnapshot.protectionBits("Protection:Card.MultiColor:multicolored", o));
        AssertJUnit.assertTrue(o.isEmpty());
        AssertJUnit.assertEquals(0, CombatSnapshot.protectionBits("Protection:Card.Human:Humans", o));
        AssertJUnit.assertEquals(List.of("Card.Human"), o);
        AssertJUnit.assertEquals(0, CombatSnapshot.protectionBits("Flying", o));
    }
}
