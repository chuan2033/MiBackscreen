package hook.HyperBackscreen.common;

import org.junit.Test;
import static org.junit.Assert.*;

public class PickupSessionsTest {
    @Test public void repaintAfterIdleKeepsOriginalPageSession() {
        PickupSessions sessions = new PickupSessions();
        String first = sessions.forCard("A", 100, true);
        assertEquals(first, sessions.forCard("B", 200, true));
        assertEquals(first, sessions.forCard("A", 10_000, false));
        assertEquals(first, sessions.forCard("B", 11_000, false));
        assertNotEquals(first, sessions.forCard("A", 12_000, true));
    }

    @Test public void repaintDoesNotExtendHostBatchWindow() {
        PickupSessions sessions = new PickupSessions();
        String first = sessions.forCard("A", 100, true);
        sessions.forCard("A", 1900, false);
        assertNotEquals(first, sessions.forCard("B", 2200, true));
        assertEquals(first, sessions.forCard("A", 2300, false));
    }
}
