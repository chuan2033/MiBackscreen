package hook.HyperBackscreen.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemeResourceAccessTest {
    @Test
    public void grantablePathIncludesThemeMagicAndRearScreenRuntimeAssets() {
        assertTrue(ThemeResourceAccess.isGrantablePath(
                "/data/system/theme_magic/users/0/rearScreen/a/editConfig"));
        assertTrue(ThemeResourceAccess.isGrantablePath(
                "/data/system/theme/rearScreen/rearscreen_abc_123.mrc"));
        assertTrue(ThemeResourceAccess.isGrantablePath(
                "/data/system/theme/rearScreen"));

        assertFalse(ThemeResourceAccess.isGrantablePath(
                "/data/system/theme/rights/rearscreen_abc.mra"));
        assertFalse(ThemeResourceAccess.isGrantablePath(
                "/data/system/theme/rearScreenWhite/rearscreen_abc.mrc"));
        assertFalse(ThemeResourceAccess.isGrantablePath(
                "/data/local/tmp/rearscreen_abc.mrc"));
    }
}
