package hook.HyperBackscreen.common;

import java.util.Collections;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostTextTest {
    @Test public void fallbackFollowsChineseAndEnglishLocales() {
        assertEquals("中文", ModuleStrings.fallback(Locale.SIMPLIFIED_CHINESE, "中文", "English"));
        assertEquals("中文", ModuleStrings.fallback(Locale.TRADITIONAL_CHINESE, "中文", "English"));
        assertEquals("English", ModuleStrings.fallback(Locale.US, "中文", "English"));
        assertEquals("English", ModuleStrings.fallback(Locale.GERMAN, "中文", "English"));
    }
    @Test public void emptyPickupTitlesUseHostLabelInBothLayouts() {
        assertEquals("Pickup codes", PickupCodes.formatIslandText(Collections.emptyList(), "Pickup codes"));
        assertEquals("Pickup codes", PickupCodes.formatCollapsedText(Collections.emptyList(), "Pickup codes"));
        assertEquals("host label", PickupCodes.formatIslandText(Collections.emptyList(), "host label"));
    }
    @Test public void localizedEmptyLabelDoesNotChangeCodeFormatting() {
        assertEquals("12-34", PickupCodes.formatIslandText(Collections.singletonList("12-34"), "Pickup codes"));
        assertEquals("12-34", PickupCodes.formatCollapsedText(Collections.singletonList("12-34"), "取件码"));
    }
}
