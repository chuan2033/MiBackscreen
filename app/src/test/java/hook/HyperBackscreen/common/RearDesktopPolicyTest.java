package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RearDesktopPolicyTest {
    @Test
    public void parseDensityFallsBackToDefault() {
        assertEquals(280, RearDesktopPolicy.parseDensity("280"));
        assertEquals(240, RearDesktopPolicy.parseDensity(" 240 "));
        assertEquals(RearDesktopPolicy.DENSITY_UNCHANGED, RearDesktopPolicy.parseDensity("0"));
        assertEquals(RearDesktopPolicy.DEFAULT_DENSITY, RearDesktopPolicy.parseDensity("100"));
        assertEquals(RearDesktopPolicy.DEFAULT_DENSITY, RearDesktopPolicy.parseDensity("abc"));
        assertEquals(RearDesktopPolicy.DEFAULT_DENSITY, RearDesktopPolicy.parseDensity(null));
    }

    @Test
    public void normalizeDensityRejectsUnknownValues() {
        assertEquals("320", RearDesktopPolicy.normalizeDensity("320"));
        assertEquals("0", RearDesktopPolicy.normalizeDensity("0"));
        assertNull(RearDesktopPolicy.normalizeDensity("450"));
        assertNull(RearDesktopPolicy.normalizeDensity(""));
        assertNull(RearDesktopPolicy.normalizeDensity(null));
    }

    @Test
    public void acceptedDensityMatchesOptions() {
        for (int option : RearDesktopPolicy.DENSITY_OPTIONS) {
            assertTrue(RearDesktopPolicy.isAcceptedDensity(option));
        }
        assertFalse(RearDesktopPolicy.isAcceptedDensity(-1));
        assertFalse(RearDesktopPolicy.isAcceptedDensity(1));
        assertFalse(RearDesktopPolicy.isAcceptedDensity(450));
    }

    @Test
    public void remoteValueParsingUsesDefaultForMissingOrInvalid() {
        // system_server 现读远程偏好：缺失（null）与非法值都按默认 280。
        assertEquals(280, RearDesktopPolicy.parseDensity(null));
        assertEquals(280, RearDesktopPolicy.parseDensity("-5"));
        assertEquals(280, RearDesktopPolicy.parseDensity("320dpi"));
        assertEquals(320, RearDesktopPolicy.parseDensity("320"));
        assertEquals(240, RearDesktopPolicy.parseDensity("240"));
    }

    @Test
    public void restoreStateRoundTrips() {
        RearDesktopPolicy.RestoreState state = new RearDesktopPolicy.RestoreState(
                10, RearDesktopPolicy.NO_FORCED_DENSITY, false, true);
        assertEquals("2:10:-1:0:1", state.encode());
        RearDesktopPolicy.RestoreState decoded = RearDesktopPolicy.RestoreState.decode(state.encode());
        assertEquals(10, decoded.userId);
        assertEquals(RearDesktopPolicy.NO_FORCED_DENSITY, decoded.forcedDensity);
        assertFalse(decoded.ignoreOrientationRequest);
        assertTrue(decoded.densityApplied);
        assertFalse(decoded.isLegacy());

        RearDesktopPolicy.RestoreState unchanged = RearDesktopPolicy.RestoreState.decode("2:0:360:1:0");
        assertEquals(360, unchanged.forcedDensity);
        assertTrue(unchanged.ignoreOrientationRequest);
        assertFalse(unchanged.densityApplied);
    }

    @Test
    public void legacyRecordDecodesWithoutUser() {
        RearDesktopPolicy.RestoreState legacy = RearDesktopPolicy.RestoreState.decode("360:1");
        assertTrue(legacy.isLegacy());
        assertEquals(RearDesktopPolicy.USER_UNKNOWN, legacy.userId);
        assertEquals(360, legacy.forcedDensity);
        assertTrue(legacy.ignoreOrientationRequest);
        assertTrue("旧版会话总是改过密度", legacy.densityApplied);
        // 旧版记录部分恢复后按当前格式写回，用户仍未知。
        RearDesktopPolicy.RestoreState rewritten = RearDesktopPolicy.RestoreState.decode(
                legacy.withDensityApplied(false).encode());
        assertTrue(rewritten.isLegacy());
        assertFalse(rewritten.densityApplied);
    }

    @Test
    public void restoreStateRejectsMalformedValues() {
        assertNull(RearDesktopPolicy.RestoreState.decode(null));
        assertNull(RearDesktopPolicy.RestoreState.decode(""));
        assertNull(RearDesktopPolicy.RestoreState.decode("360"));
        assertNull(RearDesktopPolicy.RestoreState.decode("360:2"));
        assertNull(RearDesktopPolicy.RestoreState.decode("0:0"));
        assertNull(RearDesktopPolicy.RestoreState.decode("a:b"));
        assertNull(RearDesktopPolicy.RestoreState.decode("3:0:-1:0:1"));
        assertNull(RearDesktopPolicy.RestoreState.decode("2:-3:-1:0:1"));
        assertNull(RearDesktopPolicy.RestoreState.decode("2:0:0:0:1"));
        assertNull(RearDesktopPolicy.RestoreState.decode("2:0:-1:0:2"));
        assertNull(RearDesktopPolicy.RestoreState.decode("2:0:-1:0"));
    }

    @Test
    public void densityZeroKeepsOriginalDensity() {
        assertFalse(RearDesktopPolicy.shouldApplyDensity(RearDesktopPolicy.DENSITY_UNCHANGED));
        assertTrue(RearDesktopPolicy.shouldApplyDensity(320));
        assertTrue(RearDesktopPolicy.shouldApplyDensity(280));
        assertTrue(RearDesktopPolicy.shouldApplyDensity(240));
        assertFalse(RearDesktopPolicy.shouldApplyDensity(450));

        RearDesktopPolicy.RestoreState state = new RearDesktopPolicy.RestoreState(0, 360, false, false);
        RearDesktopPolicy.RestorePlan plan = RearDesktopPolicy.planRestore(state, true);
        assertFalse("档位 0 的会话恢复时不碰密度", plan.restoreDensity);
    }

    @Test
    public void planRestoresDensityForRecordedUser() {
        RearDesktopPolicy.RestorePlan plan = RearDesktopPolicy.planRestore(
                new RearDesktopPolicy.RestoreState(10, RearDesktopPolicy.NO_FORCED_DENSITY, true, true), false);
        assertTrue(plan.restoreDensity);
        assertEquals(10, plan.densityUserId);
        assertEquals(RearDesktopPolicy.NO_FORCED_DENSITY, plan.forcedDensity);
        assertTrue(plan.ignoreOrientationRequest);
    }

    @Test
    public void legacyPlanWritesDensityOnlyOnSystemUserOnlyDevice() {
        RearDesktopPolicy.RestoreState legacy = RearDesktopPolicy.RestoreState.decode("-1:0");
        RearDesktopPolicy.RestorePlan single = RearDesktopPolicy.planRestore(legacy, true);
        assertTrue(single.restoreDensity);
        assertEquals(RearDesktopPolicy.USER_SYSTEM, single.densityUserId);
        RearDesktopPolicy.RestorePlan multi = RearDesktopPolicy.planRestore(legacy, false);
        assertFalse("多用户设备上不知道旧记录属于谁，不写密度", multi.restoreDensity);
        assertFalse(multi.ignoreOrientationRequest);
    }

    @Test
    public void remainingRecordAfterPartialFailureRetriesOnlyWhatFailed() {
        RearDesktopPolicy.RestoreState state = new RearDesktopPolicy.RestoreState(0, 360, false, true);
        RearDesktopPolicy.RestorePlan plan = RearDesktopPolicy.planRestore(state, true);
        assertNull(RearDesktopPolicy.remainingAfter(state, plan, true, true));

        RearDesktopPolicy.RestoreState orientationFailed = RearDesktopPolicy.remainingAfter(state, plan, true, false);
        assertFalse("密度已写回，不再重复", orientationFailed.densityApplied);
        RearDesktopPolicy.RestorePlan retry = RearDesktopPolicy.planRestore(orientationFailed, true);
        assertFalse(retry.restoreDensity);
        assertNull(RearDesktopPolicy.remainingAfter(orientationFailed, retry, false, true));

        RearDesktopPolicy.RestoreState densityFailed = RearDesktopPolicy.remainingAfter(state, plan, false, true);
        assertTrue(densityFailed.densityApplied);
        assertEquals(360, densityFailed.forcedDensity);

        RearDesktopPolicy.RestoreState legacy = RearDesktopPolicy.RestoreState.decode("360:1");
        RearDesktopPolicy.RestorePlan skipped = RearDesktopPolicy.planRestore(legacy, false);
        assertNull("跳过的密度不算待恢复", RearDesktopPolicy.remainingAfter(legacy, skipped, false, true));
    }

    @Test
    public void forcedDensityOnlyWhenBaseDiffersFromInitial() {
        assertEquals(RearDesktopPolicy.NO_FORCED_DENSITY, RearDesktopPolicy.forcedDensityOf(450, 450));
        assertEquals(360, RearDesktopPolicy.forcedDensityOf(360, 450));
        assertEquals(RearDesktopPolicy.NO_FORCED_DENSITY, RearDesktopPolicy.forcedDensityOf(0, 450));
    }
}
