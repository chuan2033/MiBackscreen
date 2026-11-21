package hook.HyperBackscreen.bridge;

import android.content.SharedPreferences;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import hook.HyperBackscreen.common.Constants;

import static org.junit.Assert.*;

public class PrefsBridgeReadTest {
    private static final String KEY = "disable_long_press_edit";
    private static final String PACKAGES = "double_tap_wake_disabled_packages";
    private static final String PENDING = "__pending_ui__";

    @Test public void panelWritesOnlyQuickPanelToggles() {
        assertTrue(PrefsBridge.isPanelWritableKey(Constants.KEY_DISABLE_LONG_PRESS_EDIT));
        assertTrue(PrefsBridge.isPanelWritableKey(Constants.KEY_REMOVE_WALLPAPER_LIMIT));
        assertTrue(PrefsBridge.isPanelWritableKey(Constants.KEY_ENABLE_BATTERY_RING));
        assertTrue(PrefsBridge.isPanelWritableKey(Constants.KEY_REMOVE_APP_CARD_LIMIT));
        assertTrue(PrefsBridge.isPanelWritableKey(Constants.KEY_ENABLE_REAR_LYRIC));
        assertTrue(PrefsBridge.isPanelWritableKey(Constants.KEY_REAR_LYRIC_IN_AOD));
        assertFalse(PrefsBridge.isPanelWritableKey(Constants.KEY_ENABLE_18_PRO_FEATURES));
        assertFalse(PrefsBridge.isPanelWritableKey(Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME));
        assertFalse(PrefsBridge.isPanelWritableKey(null));
    }

    @Test public void eighteenProDefaultsOnAndOfflineDisableSurvivesStaleRemote() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        String key = Constants.KEY_ENABLE_18_PRO_FEATURES;
        assertTrue(PrefsBridge.readForUi(local.prefs, null, key, PrefsBridge.DEFAULT_ENABLE_18_PRO_FEATURES));
        assertTrue(PrefsBridge.writeEnable18ProFeatures(local.prefs, null, false));
        remote.values.put(key, true);
        assertFalse(PrefsBridge.readForUi(local.prefs, remote.prefs, key, true));
        assertEquals(false, local.values.get(PENDING + key));
        assertEquals(true, remote.values.get(key));
    }

    @Test public void eighteenProRemoteCommitFailureKeepsPreviousConfiguration() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        String key = Constants.KEY_ENABLE_18_PRO_FEATURES;
        local.values.put(key, true);
        remote.values.put(key, true);
        remote.commitSucceeds = false;
        assertFalse(PrefsBridge.writeEnable18ProFeatures(local.prefs, remote.prefs, false));
        assertEquals(true, local.values.get(key));
        assertEquals(true, remote.values.get(key));
        assertFalse(local.values.containsKey(PENDING + key));
    }

    @Test public void eighteenProCommittedWriteClearsPendingAndPreservesIndependentSettings() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        String key = Constants.KEY_ENABLE_18_PRO_FEATURES;
        local.values.put(PENDING + key, true);
        local.values.put(Constants.KEY_ENABLE_APP_CARD, true);
        remote.values.put(Constants.KEY_ENABLE_APP_CARD, true);
        for (boolean enabled : new boolean[]{false, true}) {
            assertTrue(PrefsBridge.writeEnable18ProFeatures(local.prefs, remote.prefs, enabled));
            assertEquals(enabled, remote.values.get(key));
            assertEquals(enabled, local.values.get(key));
            assertFalse(local.values.containsKey(PENDING + key));
            assertEquals(true, local.values.get(Constants.KEY_ENABLE_APP_CARD));
            assertEquals(true, remote.values.get(Constants.KEY_ENABLE_APP_CARD));
        }
    }

    @Test public void eighteenProOfflineDiskFailureIsReported() {
        MemoryPrefs local = new MemoryPrefs();
        local.commitSucceeds = false;
        assertFalse(PrefsBridge.writeEnable18ProFeatures(local.prefs, null, false));
    }

    @Test public void pendingFalseSurvivesStaleRemoteAndRepeatedReads() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        local.values.put(KEY, false);
        local.values.put(PENDING + KEY, false);
        remote.values.put(KEY, true);
        for (int i = 0; i < 2; i++) {
            assertFalse(PrefsBridge.readForUi(local.prefs, remote.prefs, KEY, true));
            assertEquals(false, local.values.get(KEY));
            assertEquals(false, local.values.get(PENDING + KEY));
            assertEquals(true, remote.values.get(KEY));
        }
    }

    @Test public void pendingEmptyPackageListDoesNotRestoreOldSelection() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        local.values.put(PACKAGES, "");
        local.values.put(PENDING + PACKAGES, "");
        remote.values.put(PACKAGES, "old.package");
        assertEquals("", PrefsBridge.readStringForUi(local.prefs, remote.prefs, PACKAGES, ""));
        assertEquals("", local.values.get(PACKAGES));
        assertTrue(local.values.containsKey(PENDING + PACKAGES));
    }

    @Test public void pendingPickupSelectionUsesTheSameReadPolicy() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        String key = "pickup_island_selection";
        local.values.put(PENDING + key, "new-selection");
        remote.values.put(key, "old-selection");
        assertEquals("new-selection", PrefsBridge.readStringForUi(local.prefs, remote.prefs, key, ""));
    }

    @Test public void remoteValueBecomesAuthoritativeAfterPendingIsCleared() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        local.values.put(PENDING + KEY, true);
        remote.values.put(KEY, false);
        assertTrue(PrefsBridge.readForUi(local.prefs, remote.prefs, KEY, false));
        local.values.remove(PENDING + KEY);
        assertFalse(PrefsBridge.readForUi(local.prefs, remote.prefs, KEY, true));
        assertEquals(false, local.values.get(KEY));
    }

    @Test public void remoteReadsRefreshBothCacheTypes() {
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        remote.values.put(KEY, false);
        remote.values.put(PACKAGES, "remote.package");
        assertFalse(PrefsBridge.readForUi(local.prefs, remote.prefs, KEY, true));
        assertEquals("remote.package", PrefsBridge.readStringForUi(local.prefs, remote.prefs, PACKAGES, ""));
        assertEquals(remote.values, local.values);
    }

    @Test public void offlineReadsUseCacheAndDefaults() {
        MemoryPrefs local = new MemoryPrefs();
        assertTrue(PrefsBridge.readForUi(local.prefs, null, KEY, true));
        assertEquals("", PrefsBridge.readStringForUi(local.prefs, null, PACKAGES, ""));
        local.values.put(KEY, false);
        local.values.put(PACKAGES, "cached.package");
        assertFalse(PrefsBridge.readForUi(local.prefs, null, KEY, true));
        assertEquals("cached.package", PrefsBridge.readStringForUi(local.prefs, null, PACKAGES, ""));
    }

    /** Only the read/cache operations under test; unexpected API calls fail the test. */
    @Test public void rearDesktopDensityMigratesOnlySavedLocalValueAndRemoteWins() {
        String key = Constants.KEY_REAR_DESKTOP_DENSITY;
        MemoryPrefs local = new MemoryPrefs();
        MemoryPrefs remote = new MemoryPrefs();
        PrefsBridge.syncSavedStringKey(local.prefs, remote.prefs, key);
        assertFalse("没保存过的档位不写默认值", remote.values.containsKey(key));
        assertFalse(local.values.containsKey(key));

        local.values.put(key, "240");
        PrefsBridge.syncSavedStringKey(local.prefs, remote.prefs, key);
        assertEquals("240", remote.values.get(key));

        MemoryPrefs upgraded = new MemoryPrefs();
        MemoryPrefs existing = new MemoryPrefs();
        upgraded.values.put(key, "240");
        existing.values.put(key, "320");
        PrefsBridge.syncSavedStringKey(upgraded.prefs, existing.prefs, key);
        assertEquals("已有远程值优先", "320", existing.values.get(key));
        assertEquals("320", upgraded.values.get(key));

        MemoryPrefs pendingLocal = new MemoryPrefs();
        MemoryPrefs pendingRemote = new MemoryPrefs();
        pendingLocal.values.put(PENDING + key, "0");
        pendingRemote.values.put(key, "320");
        PrefsBridge.syncSavedStringKey(pendingLocal.prefs, pendingRemote.prefs, key);
        assertFalse("待同步值由补交处理，不被远程旧值覆盖", pendingLocal.values.containsKey(key));
    }

    private static final class MemoryPrefs {
        boolean commitSucceeds = true;
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "contains" -> values.containsKey(args[0]);
                    case "getBoolean", "getString" -> values.getOrDefault(args[0], args[1]);
                    case "edit" -> editor();
                    default -> throw new AssertionError(method.getName());
                });

        private SharedPreferences.Editor editor() {
            Map<String, Object> staged = new HashMap<>();
            Set<String> removed = new HashSet<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "putBoolean", "putString" -> { staged.put((String) args[0], args[1]); yield proxy; }
                        case "remove" -> { removed.add((String) args[0]); yield proxy; }
                        case "apply" -> {
                            removed.forEach(values::remove); values.putAll(staged); yield null;
                        }
                        case "commit" -> {
                            if (commitSucceeds) { removed.forEach(values::remove); values.putAll(staged); }
                            yield commitSucceeds;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
        }
    }
}
