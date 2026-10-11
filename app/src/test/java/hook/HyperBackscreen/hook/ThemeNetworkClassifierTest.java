package hook.HyperBackscreen.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import hook.HyperBackscreen.common.Constants;

public class ThemeNetworkClassifierTest {
    @Test
    public void spoofsAiAppListAndSubjectRequests() {
        assertTrue(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                Constants.THEME_AI_APP_PAGE_PATH,
                null));
        assertTrue(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                Constants.THEME_AI_APP_SUBJECT_PATH + "foo",
                null));
    }

    @Test
    public void spoofsOnlyAiAppThemeDetailsWithAiFlag() {
        String detailPath = Constants.THEME_AI_APP_DETAIL_PATH + "abc";

        assertTrue(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                detailPath,
                Constants.THEME_AI_APP_REQUEST_FLAG_VALUE));
        assertFalse(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                detailPath,
                "31"));
    }

    @Test
    public void spoofsRearAiWallpaperRequests() {
        assertTrue(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                "/native/page/v3/AIWallpaper/rear",
                null));
        assertTrue(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                "/native/page/v3/rear/phone_case/diy",
                null));
        assertTrue(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                "/native/page/v3/backscreen/ai_photo",
                null));
    }

    @Test
    public void keepsOrdinaryRearWallpaperDetailsOnRealDevice() {
        assertFalse(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                "/native/page/v3/theme/regular-wallpaper",
                null));
        assertFalse(ThemeNetworkClassifier.shouldSpoofDeviceIdentity(
                "/native/page/v3/theme/regular-wallpaper",
                "31"));
    }

    @Test
    public void logsDiagnosticsForWallpaperFeatureTerms() {
        assertTrue(ThemeNetworkClassifier.shouldLogDiagnostic(
                "/native/page/v3/rear/portrait"));
        assertTrue(ThemeNetworkClassifier.shouldLogDiagnostic(
                "/native/page/v3/rear/phonecase"));
    }
}
