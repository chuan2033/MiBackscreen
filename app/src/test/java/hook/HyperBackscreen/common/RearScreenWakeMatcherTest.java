package hook.HyperBackscreen.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RearScreenWakeMatcherTest {
    @Test
    public void isRearDoubleTapWakeMatchesRearScreenWakeRequests() {
        assertTrue(RearScreenWakeMatcher.isRearDoubleTapWake(1, Constants.WAKE_REASON_DOUBLE_TAP));
        assertTrue(RearScreenWakeMatcher.isRearDoubleTapWake(1, "android.policy:GESTURE"));
        assertTrue(RearScreenWakeMatcher.isRearDoubleTapWake(1, null));

        assertFalse(RearScreenWakeMatcher.isRearDoubleTapWake(0, Constants.WAKE_REASON_DOUBLE_TAP));
    }

    @Test
    public void packageListMatchesAnyCandidatePackage() {
        String rawPackages = "com.metek.ultraman.mi\ncom.tencent.tmgp.sgame";

        assertTrue(RearScreenWakeMatcher.matchesAnyPackage(
                rawPackages,
                "com.xiaomi.gamecenter.sdk.service",
                "com.metek.ultraman.mi"));

        assertFalse(RearScreenWakeMatcher.matchesAnyPackage(
                rawPackages,
                "com.xiaomi.gamecenter.sdk.service",
                "com.miui.home"));

        assertFalse(RearScreenWakeMatcher.matchesAnyPackage(rawPackages, (String[]) null));
    }
}
