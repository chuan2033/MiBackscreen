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
}
