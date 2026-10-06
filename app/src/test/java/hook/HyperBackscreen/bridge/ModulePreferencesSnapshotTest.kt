package hook.HyperBackscreen.bridge

import android.content.SharedPreferences
import hook.HyperBackscreen.common.Constants
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ModulePreferencesSnapshotTest {
    private class Cache {
        val values = mutableMapOf<String, Any>()
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        var reads = 0
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> { reads++; values.toMap() }
                "registerOnSharedPreferenceChangeListener" -> {
                    listeners.add(args[0] as SharedPreferences.OnSharedPreferenceChangeListener); null
                }
                "unregisterOnSharedPreferenceChangeListener" -> {
                    listeners.remove(args[0] as SharedPreferences.OnSharedPreferenceChangeListener); null
                }
                else -> error("Observation must not read remote preferences or edit the cache: ${method.name}")
            }
        } as SharedPreferences

        fun changed(key: String?) {
            listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, key) }
        }
        fun put(key: String, value: Any) { values[key] = value; changed(key) }
    }

    @Test fun emptyCachePreservesAllExistingDefaults() {
        val cache = Cache()
        val snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertEquals(ModulePreferencesSnapshot(true, true, true, true, false, true, false, true, false, "", true), snapshot)
        assertEquals(1, cache.reads)
        assertTrue(cache.values.isEmpty())
    }

    @Test fun snapshotIncludesEveryHookSettingAndPackageList() {
        val cache = Cache()
        cache.values.putAll(mapOf(
            Constants.KEY_DISABLE_LONG_PRESS_EDIT to false,
            Constants.KEY_REMOVE_WALLPAPER_LIMIT to false,
            Constants.KEY_ENABLE_APP_CARD to false,
            Constants.KEY_ENABLE_18_PRO_FEATURES to false,
            Constants.KEY_REMOVE_APP_CARD_LIMIT to false,
            Constants.KEY_FIX_REAR_SCREEN_APPLY to true,
            Constants.KEY_ENABLE_PICKUP to false,
            Constants.KEY_DISABLE_REAR_SCREEN_COVER to true,
            Constants.KEY_THEME_SETTINGS_SHORTCUT to false,
            Constants.KEY_DISABLE_DOUBLE_TAP_WAKE to true,
            Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES to "selected.package",
        ))
        assertEquals(ModulePreferencesSnapshot(false, false, false, false, true, false, true, false, true,
            "selected.package", false), ModulePreferencesSnapshot.read(cache.preferences))
    }

    @Test fun pending18ProToggleIsObservedAndPreservesIndependentSettings() {
        val cache = Cache()
        val pending = PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_ENABLE_18_PRO_FEATURES
        cache.values[Constants.KEY_ENABLE_18_PRO_FEATURES] = true
        val before = ModulePreferencesSnapshot.read(cache.preferences)
        val snapshots = mutableListOf<ModulePreferencesSnapshot>()
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            snapshots += ModulePreferencesSnapshot.read(cache.preferences)
        }).use {
            cache.put(pending, false)
            cache.put(Constants.KEY_ENABLE_18_PRO_FEATURES, false)
            cache.values.remove(pending)
            cache.changed(pending)
            cache.put(Constants.KEY_ENABLE_18_PRO_FEATURES, true)
        }
        assertEquals(listOf(false, false, false, true), snapshots.map { it.enable18ProFeatures })
        snapshots.forEach { assertEquals(before, it.copy(enable18ProFeatures = true)) }
    }

    @Test fun panelWritesRefreshAllThreeSwitchesWithoutServiceReconnect() {
        val cache = Cache()
        val snapshots = mutableListOf<ModulePreferencesSnapshot>()
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            snapshots += ModulePreferencesSnapshot.read(cache.preferences)
        }).use {
            cache.put(Constants.KEY_DISABLE_LONG_PRESS_EDIT, false)
            cache.put(Constants.KEY_REMOVE_WALLPAPER_LIMIT, false)
            cache.put(Constants.KEY_REMOVE_APP_CARD_LIMIT, false)
        }
        assertEquals(3, snapshots.size)
        assertFalse(snapshots[0].disableLongPress)
        assertFalse(snapshots[1].removeWallpaperLimit)
        assertFalse(snapshots[2].removeAppCardLimit)
        assertTrue(cache.listeners.isEmpty())
    }

    @Test fun batteryRingPendingSettingIsObservedIndependently() {
        val cache = Cache()
        var snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertFalse(snapshot.enableBatteryRing)
        val before = snapshot
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        }).use {
            cache.put(PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_ENABLE_BATTERY_RING, true)
            assertTrue(snapshot.enableBatteryRing)
            assertEquals(before, snapshot.copy(enableBatteryRing = false))
        }
    }

    @Test fun lateServiceReconciliationRefreshesPickupToggle() {
        val cache = Cache()
        var snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertTrue(snapshot.enablePickup)
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        }).use {
            cache.put(Constants.KEY_ENABLE_PICKUP, false)
            assertFalse(snapshot.enablePickup)
        }
    }

    @Test fun pendingFalseAndEmptyListOverrideStaleCacheUntilCleared() {
        val cache = Cache()
        val pendingToggle = PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_ENABLE_PICKUP
        val pendingList = PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES
        cache.values[Constants.KEY_ENABLE_PICKUP] = true
        cache.values[Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES] = "old.package"
        cache.values[pendingToggle] = false
        cache.values[pendingList] = ""
        val snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertFalse(snapshot.enablePickup)
        assertEquals("", snapshot.doubleTapWakeDisabledPackages)
        cache.values.remove(pendingToggle)
        cache.values.remove(pendingList)
        val synced = ModulePreferencesSnapshot.read(cache.preferences)
        assertTrue(synced.enablePickup)
        assertEquals("old.package", synced.doubleTapWakeDisabledPackages)
    }

    @Test fun queuedInvalidationsReadNewestValueInsteadOfReplayingOldSnapshot() {
        val cache = Cache()
        val queued = mutableListOf<() -> Unit>()
        val snapshots = mutableListOf<ModulePreferencesSnapshot>()
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            queued += { snapshots += ModulePreferencesSnapshot.read(cache.preferences) }
        }).use {
            cache.put(Constants.KEY_ENABLE_PICKUP, false)
            cache.put(Constants.KEY_ENABLE_PICKUP, true)
            queued.forEach { it() }
        }
        assertEquals(2, snapshots.size)
        assertTrue(snapshots.all { it.enablePickup })
    }

    @Test fun pendingChangesAndClearInvalidateButUnrelatedKeysAndDisposedListenersDoNot() {
        val cache = Cache()
        var notifications = 0
        val subscription = ModulePreferencesSnapshot.observe(cache.preferences, Runnable { notifications++ })
        cache.changed(Constants.KEY_FLOATING_NAV_BAR)
        cache.changed(Constants.KEY_PICKUP_ISLAND_SELECTION)
        assertEquals(0, notifications)
        cache.changed(PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_ENABLE_PICKUP)
        cache.changed(null)
        assertEquals(2, notifications)
        subscription.close()
        cache.put(Constants.KEY_ENABLE_PICKUP, false)
        assertEquals(2, notifications)
    }
}
