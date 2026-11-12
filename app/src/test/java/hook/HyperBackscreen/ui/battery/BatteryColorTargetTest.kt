package hook.HyperBackscreen.ui.battery

import hook.HyperBackscreen.batteryring.BatteryColorOverrides
import hook.HyperBackscreen.batteryring.parseBatteryColor
import hook.HyperBackscreen.bridge.ModulePreferencesSnapshot
import hook.HyperBackscreen.common.Constants
import org.junit.Assert.*
import org.junit.Test

class BatteryColorTargetTest {
    @Test fun ringAndPercentUseDistinctKeysPerState() {
        val keys = BatteryColorTarget.entries.flatMap { target ->
            BatteryColorSlot.entries.map { target.keyFor(it) }
        }
        assertEquals(6, keys.toSet().size)
        // 电量环沿用旧键，老用户已有的颜色不丢。
        assertEquals(Constants.KEY_BATTERY_COLOR_IDLE, BatteryColorTarget.RING.keyFor(BatteryColorSlot.IDLE))
        assertEquals(Constants.KEY_BATTERY_COLOR_CHARGING, BatteryColorTarget.RING.keyFor(BatteryColorSlot.CHARGING))
        assertEquals(Constants.KEY_BATTERY_COLOR_LOW, BatteryColorTarget.RING.keyFor(BatteryColorSlot.LOW))
        assertEquals(
            Constants.KEY_BATTERY_PERCENT_COLOR_LOW,
            BatteryColorTarget.PERCENT.keyFor(BatteryColorSlot.LOW),
        )
    }

    @Test fun withChangesOnlyOneSlot() {
        val base = BatteryColorOverrides(idle = "#111111", charging = "#222222", low = "#333333")
        val next = base.with(BatteryColorSlot.CHARGING, "")
        assertEquals(BatteryColorOverrides(idle = "#111111", charging = "", low = "#333333"), next)
        assertEquals("", next.rawFor(BatteryColorSlot.CHARGING))
        assertEquals("#333333", next.rawFor(BatteryColorSlot.LOW))
    }

    @Test fun snapshotSplitsRingAndPercentColors() {
        val snapshot = ModulePreferencesSnapshot(
            disableLongPress = true,
            removeWallpaperLimit = true,
            enableAppCard = true,
            removeAppCardLimit = true,
            fixRearScreenApply = false,
            disableRearScreenCover = false,
            themeSettingsShortcut = false,
            disableDoubleTapWake = false,
            doubleTapWakeDisabledPackages = "",
            enable18ProFeatures = true,
            batteryColorIdle = "#FF1DCD3A",
            batteryPercentColorIdle = "#FFFFFFFF",
        )
        assertEquals("#FF1DCD3A", batteryRingColorsOf(snapshot).idle)
        assertEquals("#FFFFFFFF", batteryPercentColorsOf(snapshot).idle)
        assertEquals("", batteryPercentColorsOf(snapshot).charging)
    }

    @Test fun contrastPresetIsOpaqueWhite() {
        assertEquals(0xFFFFFFFF.toInt(), parseBatteryColor(BatteryPercentContrastColor))
    }
}
