package hook.HyperBackscreen.hook;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SizeCappedListTest {
    @Test public void capacityBypassDoesNotDropTailDuringSave() {
        List<Integer> original = new ArrayList<>();
        for (int i = 0; i < 30; i++) original.add(i);
        SizeCappedList bypass = new SizeCappedList(original, 14);
        assertEquals(14, bypass.size());
        assertTrue(bypass.contains(29)); // Duplicate checks still see entries above the limit.
        bypass.add(30);
        ArrayList<Object> saved = new ArrayList<>(bypass);
        assertEquals(31, saved.size());
        assertEquals(30, saved.get(30));
        assertEquals(30, original.size());
    }
}
