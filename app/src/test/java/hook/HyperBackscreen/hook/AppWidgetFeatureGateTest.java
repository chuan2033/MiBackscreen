package hook.HyperBackscreen.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import hook.HyperBackscreen.common.Constants;
import org.junit.Test;

public class AppWidgetFeatureGateTest {
    @Test
    public void recognizesSubscreenAppWidgetSecureSetting() {
        assertTrue(AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                "subscreen_app_widget_enable"));
    }

    @Test
    public void ignoresOtherSecureSettings() {
        assertFalse(AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                "theme_rear_widget"));
        assertFalse(AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(null));
    }

    @Test
    public void prefersRuntimeDexNameForDeviceConfigHook() {
        assertTrue("o2.j".equals(Constants.SUBSCREEN_DEVICE_CONFIG_CLASS));
    }
}
