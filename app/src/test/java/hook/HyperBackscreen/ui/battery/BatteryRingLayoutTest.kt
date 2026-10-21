package hook.HyperBackscreen.ui.battery

import hook.HyperBackscreen.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryRingLayoutTest {
    private fun layout(vararg pairs: Pair<String, String>): BatteryRingLayout {
        val values = pairs.toMap()
        return BatteryRingLayout.of(BatteryPercentPosition.RIGHT) { values[it] }
    }

    @Test
    fun missingWeightKeepsLegacyBold() {
        // 旧版本没有这个键：读到的空值要落在 700，升级后背屏数字观感不变。
        assertEquals(700, layout().percentTextWeight)
        assertEquals(700, BatteryRingLayout.defaultFor(BatteryPercentPosition.RIGHT).percentTextWeight)
    }

    @Test
    fun weightIsQuantizedToHundreds() {
        assertEquals(500, layout(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to "537").percentTextWeight)
        assertEquals(600, layout(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to "561").percentTextWeight)
        assertEquals(300, layout(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to "349").percentTextWeight)
    }

    @Test
    fun weightIsClampedToSupportedRange() {
        assertEquals(300, layout(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to "100").percentTextWeight)
        assertEquals(900, layout(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to "1200").percentTextWeight)
        assertEquals(700, layout(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to "not-a-number").percentTextWeight)
    }

    @Test
    fun weightIsPersistedWithTheOtherLayoutEntries() {
        val layout = BatteryRingLayout.defaultFor(BatteryPercentPosition.RIGHT)
            .copy(percentTextWeight = 400)
        val entries = layout.entries(BatteryPercentPosition.RIGHT).toMap()
        assertEquals("400", entries[Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT])
        // 落盘的每一项都能按 of() 原样读回来。
        assertEquals(400, BatteryRingLayout.of(BatteryPercentPosition.RIGHT) { entries[it] }.percentTextWeight)
    }
}
