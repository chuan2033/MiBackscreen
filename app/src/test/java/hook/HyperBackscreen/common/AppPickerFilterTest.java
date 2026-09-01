package hook.HyperBackscreen.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppPickerFilterTest {
    @Test
    public void blankQueryShowsEveryInstalledApp() {
        assertTrue(AppPickerFilter.matches("王者荣耀", "com.tencent.tmgp.sgame", ""));
        assertTrue(AppPickerFilter.matches("王者荣耀", "com.tencent.tmgp.sgame", "   "));
    }

    @Test
    public void queryMatchesAppLabelOrPackageNameIgnoringCase() {
        assertTrue(AppPickerFilter.matches("王者荣耀", "com.tencent.tmgp.sgame", "王者"));
        assertTrue(AppPickerFilter.matches("Ultraman", "com.metek.ultraman.mi", "ULTRA"));
        assertTrue(AppPickerFilter.matches("奥特曼", "com.metek.ultraman.mi", "metek"));

        assertFalse(AppPickerFilter.matches("王者荣耀", "com.tencent.tmgp.sgame", "ultraman"));
    }
}
