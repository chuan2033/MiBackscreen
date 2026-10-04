package hook.HyperBackscreen.bridge;

import android.content.SharedPreferences;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PrefsBridgeReadTest {
    private static final String KEY = "disable_long_press_edit";
    private static final String PACKAGES = "double_tap_wake_disabled_packages";
    private static final String PENDING = "__pending_ui__";

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
    private static final class MemoryPrefs {
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
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "putBoolean", "putString" -> { staged.put((String) args[0], args[1]); yield proxy; }
                        case "apply" -> { values.putAll(staged); yield null; }
                        default -> throw new AssertionError(method.getName());
                    });
        }
    }
}
