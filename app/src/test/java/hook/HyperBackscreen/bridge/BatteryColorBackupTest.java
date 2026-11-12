package hook.HyperBackscreen.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import hook.HyperBackscreen.common.Constants;

public class BatteryColorBackupTest {
    private static final String[] COLOR_KEYS = {
            Constants.KEY_BATTERY_COLOR_IDLE,
            Constants.KEY_BATTERY_COLOR_CHARGING,
            Constants.KEY_BATTERY_COLOR_LOW,
            Constants.KEY_BATTERY_PERCENT_COLOR_IDLE,
            Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING,
            Constants.KEY_BATTERY_PERCENT_COLOR_LOW,
    };

    @Test
    public void ringAndPercentColorsAcceptHexAndEmpty() {
        for (String key : COLOR_KEYS) {
            assertEquals(key, "", PrefsBridge.normalizeBackupString(key, "  "));
            assertEquals(key, "#FFFFFFFF", PrefsBridge.normalizeBackupString(key, " #FFFFFFFF "));
            assertEquals(key, "#1dcd3a", PrefsBridge.normalizeBackupString(key, "#1dcd3a"));
        }
    }

    @Test
    public void ringAndPercentColorsRejectGarbage() {
        for (String key : COLOR_KEYS) {
            assertNull(key, PrefsBridge.normalizeBackupString(key, "white"));
            assertNull(key, PrefsBridge.normalizeBackupString(key, "#12345"));
        }
    }
}
