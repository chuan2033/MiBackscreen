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
        assertEquals(
            ModulePreferencesSnapshot(
                disableLongPress = PrefsBridge.DEFAULT_DISABLE_LONG_PRESS_EDIT,
                removeWallpaperLimit = PrefsBridge.DEFAULT_REMOVE_WALLPAPER_LIMIT,
                enableAppCard = PrefsBridge.DEFAULT_ENABLE_APP_CARD,
                removeAppCardLimit = PrefsBridge.DEFAULT_REMOVE_APP_CARD_LIMIT,
                fixRearScreenApply = PrefsBridge.DEFAULT_FIX_REAR_SCREEN_APPLY,
                disableRearScreenCover = PrefsBridge.DEFAULT_DISABLE_REAR_SCREEN_COVER,
                themeSettingsShortcut = PrefsBridge.DEFAULT_THEME_SETTINGS_SHORTCUT,
                disableDoubleTapWake = PrefsBridge.DEFAULT_DISABLE_DOUBLE_TAP_WAKE,
                doubleTapWakeDisabledPackages = PrefsBridge.DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                enable18ProFeatures = PrefsBridge.DEFAULT_ENABLE_18_PRO_FEATURES,
            ),
            snapshot
        )
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
            Constants.KEY_DISABLE_REAR_SCREEN_COVER to true,
            Constants.KEY_THEME_SETTINGS_SHORTCUT to false,
            Constants.KEY_DISABLE_DOUBLE_TAP_WAKE to true,
            Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES to "selected.package",
        ))
        // 人脸兼容已拆成独立键；新键未写过时沿用旧开关，这里 fix_rear_screen_apply=true 会带出 true。
        val expected = ModulePreferencesSnapshot(
            disableLongPress = false,
            removeWallpaperLimit = false,
            enableAppCard = false,
            removeAppCardLimit = false,
            fixRearScreenApply = true,
            disableRearScreenCover = true,
            themeSettingsShortcut = false,
            disableDoubleTapWake = true,
            doubleTapWakeDisabledPackages = "selected.package",
            enable18ProFeatures = false,
        ).copy(faceEnrollmentCompat = true)
        assertEquals(expected, ModulePreferencesSnapshot.read(cache.preferences))
    }

    @Test fun faceEnrollmentFallsBackToLegacySwitchUntilIndependentKeyIsWritten() {
        val cache = Cache()
        cache.values[Constants.KEY_FIX_REAR_SCREEN_APPLY] = true
        assertTrue(ModulePreferencesSnapshot.read(cache.preferences).faceEnrollmentCompat)

        // 一旦写了新键（哪怕是 false），就以新键为准，不再跟随旧开关。
        cache.values[Constants.KEY_FACE_ENROLLMENT_COMPAT] = false
        assertFalse(ModulePreferencesSnapshot.read(cache.preferences).faceEnrollmentCompat)

        cache.values[Constants.KEY_FIX_REAR_SCREEN_APPLY] = false
        cache.values[Constants.KEY_FACE_ENROLLMENT_COMPAT] = true
        val snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertTrue(snapshot.faceEnrollmentCompat)
        assertFalse(snapshot.fixRearScreenApply)
    }

    @Test fun keepRearScreenOnInGameIsObservedIndependently() {
        val cache = Cache()
        var snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertFalse(snapshot.keepRearScreenOnInGame)
        val before = snapshot
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        }).use {
            cache.put(Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME, true)
            assertTrue(snapshot.keepRearScreenOnInGame)
            assertEquals(
                before,
                snapshot.copy(
                    keepRearScreenOnInGame = PrefsBridge.DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME,
                ),
            )
        }
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

    @Test fun batteryPercentPositionIsObservedIndependently() {
        val cache = Cache()
        var snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertEquals(PrefsBridge.DEFAULT_BATTERY_PERCENT_POSITION, snapshot.batteryPercentPosition)
        val before = snapshot
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        }).use {
            cache.put(Constants.KEY_BATTERY_PERCENT_POSITION, "right")
            assertEquals("right", snapshot.batteryPercentPosition)
            assertEquals(before, snapshot.copy(batteryPercentPosition = PrefsBridge.DEFAULT_BATTERY_PERCENT_POSITION))
        }
    }

    @Test fun automaticWallpaperIntervalIsObservedIndependently() {
        val cache = Cache()
        var snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertEquals(
            PrefsBridge.DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES,
            snapshot.autoWallpaperIntervalMinutes,
        )
        val before = snapshot
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        }).use {
            cache.put(Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES, "5")
            assertEquals("5", snapshot.autoWallpaperIntervalMinutes)
            assertEquals(
                before,
                snapshot.copy(
                    autoWallpaperIntervalMinutes = PrefsBridge.DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES,
                ),
            )
        }
    }

    @Test fun pendingFalseAndEmptyListOverrideStaleCacheUntilCleared() {
        val cache = Cache()
        val pendingToggle = PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_ENABLE_APP_CARD
        val pendingList = PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES
        cache.values[Constants.KEY_ENABLE_APP_CARD] = true
        cache.values[Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES] = "old.package"
        cache.values[pendingToggle] = false
        cache.values[pendingList] = ""
        val snapshot = ModulePreferencesSnapshot.read(cache.preferences)
        assertFalse(snapshot.enableAppCard)
        assertEquals("", snapshot.doubleTapWakeDisabledPackages)
        cache.values.remove(pendingToggle)
        cache.values.remove(pendingList)
        val synced = ModulePreferencesSnapshot.read(cache.preferences)
        assertTrue(synced.enableAppCard)
        assertEquals("old.package", synced.doubleTapWakeDisabledPackages)
    }

    @Test fun queuedInvalidationsReadNewestValueInsteadOfReplayingOldSnapshot() {
        val cache = Cache()
        val queued = mutableListOf<() -> Unit>()
        val snapshots = mutableListOf<ModulePreferencesSnapshot>()
        ModulePreferencesSnapshot.observe(cache.preferences, Runnable {
            queued += { snapshots += ModulePreferencesSnapshot.read(cache.preferences) }
        }).use {
            cache.put(Constants.KEY_ENABLE_APP_CARD, false)
            cache.put(Constants.KEY_ENABLE_APP_CARD, true)
            queued.forEach { it() }
        }
        assertEquals(2, snapshots.size)
        assertTrue(snapshots.all { it.enableAppCard })
    }

    @Test fun pendingChangesAndClearInvalidateButUnrelatedKeysAndDisposedListenersDoNot() {
        val cache = Cache()
        var notifications = 0
        val subscription = ModulePreferencesSnapshot.observe(cache.preferences, Runnable { notifications++ })
        cache.changed(Constants.KEY_FLOATING_NAV_BAR)
        cache.changed("unrelated_string_key")
        assertEquals(0, notifications)
        cache.changed(PrefsBridge.PENDING_UI_PREFIX + Constants.KEY_ENABLE_APP_CARD)
        cache.changed(null)
        assertEquals(2, notifications)
        subscription.close()
        cache.put(Constants.KEY_ENABLE_APP_CARD, false)
        assertEquals(2, notifications)
    }
}
