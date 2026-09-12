package hook.HyperBackscreen.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModuleUpdateStateTest {
    private static final String OLD_APK = "/data/app/old/hook.HyperBackscreen/base.apk";
    private static final String NEW_APK = "/data/app/new/hook.HyperBackscreen/base.apk";

    @Test
    public void unchangedInstallationKeepsNormalPanel() {
        ModuleUpdateState loaded = new ModuleUpdateState(OLD_APK, 8);

        assertFalse(loaded.requiresRestart(OLD_APK, 8));
        assertFalse(loaded.requiresRestart(OLD_APK, 8));
    }

    @Test
    public void upgradeBeforeFirstPanelOpeningStillRequiresRestart() {
        ModuleUpdateState loaded = new ModuleUpdateState(OLD_APK, 8);

        assertTrue(loaded.requiresRestart(NEW_APK, 9));
    }

    @Test
    public void sameVersionReplacementRequiresRestartUntilNewCodeLoads() {
        ModuleUpdateState runningProcess = new ModuleUpdateState(OLD_APK, 8);

        assertTrue(runningProcess.requiresRestart(NEW_APK, 8));
        assertTrue(runningProcess.requiresRestart(NEW_APK, 8));

        ModuleUpdateState restartedProcess = new ModuleUpdateState(NEW_APK, 8);
        assertFalse(restartedProcess.requiresRestart(NEW_APK, 8));
    }

    @Test
    public void downgradeAlsoRequiresRestart() {
        assertTrue(new ModuleUpdateState(NEW_APK, 9).requiresRestart(OLD_APK, 8));
    }

    @Test
    public void versionChangeIsDetectedEvenWhenPathIsUnchangedOrUnavailable() {
        assertTrue(new ModuleUpdateState(OLD_APK, 8).requiresRestart(OLD_APK, 9));
        assertTrue(new ModuleUpdateState(null, 8).requiresRestart(NEW_APK, 9));
        assertTrue(new ModuleUpdateState(OLD_APK, 8).requiresRestart(null, 9));
    }

    @Test
    public void missingPathAloneDoesNotClaimAnUpdate() {
        assertFalse(new ModuleUpdateState(null, 8).requiresRestart(NEW_APK, 8));
        assertFalse(new ModuleUpdateState("", 8).requiresRestart(NEW_APK, 8));
        assertFalse(new ModuleUpdateState(OLD_APK, 8).requiresRestart(null, 8));
        assertFalse(new ModuleUpdateState(OLD_APK, 8).requiresRestart("", 8));
    }
}
