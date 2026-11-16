package hook.HyperBackscreen.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RearScreenSleepPolicyTest {
    private static final int POWER_BUTTON = RearScreenSleepPolicy.GO_TO_SLEEP_REASON_POWER_BUTTON;
    private static final long ALWAYS = Constants.REAR_SCREEN_OFF_TIMEOUT_ALWAYS_MS;

    @Test
    public void keepsRearAwakeWhenAlwaysOnAndPowerButton() {
        assertTrue(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(POWER_BUTTON, ALWAYS, false));
    }

    @Test
    public void followsSystemForOtherTimeouts() {
        assertFalse(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(POWER_BUTTON, 0L, false));
        assertFalse(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(POWER_BUTTON, 60_000L, false));
        assertFalse(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(POWER_BUTTON, 1_800_000L, false));
    }

    @Test
    public void followsSystemForOtherSleepReasons() {
        // 0 应用请求，2 超时，6 睡眠键
        assertFalse(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(0, ALWAYS, false));
        assertFalse(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(
                Constants.GO_TO_SLEEP_REASON_TIMEOUT, ALWAYS, false));
        assertFalse(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(6, ALWAYS, false));
    }

    @Test
    public void scheduleWindowLetsRearSleep() {
        assertFalse(RearScreenSleepPolicy.keepsRearAwakeOnPowerButton(POWER_BUTTON, ALWAYS, true));
    }
}
