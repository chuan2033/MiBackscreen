package hook.HyperBackscreen.common;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityLaunchTest {
    @Test public void successfulLaunchRunsOnce() {
        int[] calls = {0};
        assertTrue(ActivityLaunch.attempt(() -> calls[0]++));
        assertEquals(1, calls[0]);
    }
    @Test public void missingHandlerAndDeniedLaunchReportFailure() {
        assertFalse(ActivityLaunch.attempt(() -> { throw new RuntimeException("missing handler"); }));
        assertFalse(ActivityLaunch.attempt(() -> { throw new SecurityException("denied"); }));
    }
    @Test public void failedLaunchCanBeRetriedWithoutSuppressingNextAttempt() {
        assertFalse(ActivityLaunch.attempt(() -> { throw new SecurityException(); }));
        assertTrue(ActivityLaunch.attempt(() -> {}));
    }
}
