package hook.HyperBackscreen.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RearScreenWakeMatcherTest {
    @Test
    public void isRearDoubleTapWakeOnlyMatchesRearScreenDoubleTapKey() {
        assertTrue(RearScreenWakeMatcher.isRearDoubleTapWake(1, Constants.WAKE_REASON_DOUBLE_TAP));

        assertFalse(RearScreenWakeMatcher.isRearDoubleTapWake(0, Constants.WAKE_REASON_DOUBLE_TAP));
        assertFalse(RearScreenWakeMatcher.isRearDoubleTapWake(1, "android.policy:POWER"));
        assertFalse(RearScreenWakeMatcher.isRearDoubleTapWake(1, null));
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
