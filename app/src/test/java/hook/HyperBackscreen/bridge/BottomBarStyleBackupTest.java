package hook.HyperBackscreen.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import hook.HyperBackscreen.common.Constants;

public class BottomBarStyleBackupTest {
    @Test
    public void backupAcceptsEachNavigationStyle() {
        for (String style : new String[]{"standard", "floating", "liquid", "glass"}) {
            assertEquals(style,
                    PrefsBridge.normalizeBackupString(Constants.KEY_BOTTOM_BAR_STYLE, "  " + style + "  "));
        }
    }

    @Test
    public void backupRejectsUnknownNavigationStyle() {
        assertNull(PrefsBridge.normalizeBackupString(Constants.KEY_BOTTOM_BAR_STYLE, "unknown"));
    }

    @Test
    public void localNavigationStylePreservesLiquidSelectionAfterRemoteSync() {
        assertEquals("liquid", PrefsBridge.resolveBottomBarStyle("standard", true, true));
        assertEquals("floating", PrefsBridge.resolveBottomBarStyle("standard", true, false));
        assertEquals("standard", PrefsBridge.resolveBottomBarStyle("standard", false, false));
        assertEquals("glass", PrefsBridge.resolveBottomBarStyle("glass", true, false));
    }

    @Test
    public void legacyNavigationStyleMigratesFromBooleanSettings() {
        assertEquals("liquid", PrefsBridge.resolveBottomBarStyle(null, true, true));
        assertEquals("floating", PrefsBridge.resolveBottomBarStyle(null, true, false));
        assertEquals("standard", PrefsBridge.resolveBottomBarStyle(null, false, false));
    }
}
