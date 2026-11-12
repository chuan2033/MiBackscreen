package hook.HyperBackscreen.batteryring

import org.junit.Assert.*
import org.junit.Test

class BatteryRingColorsTest {
    private val idle = BatteryRingState(percent = 80, charging = false)
    private val charging = BatteryRingState(percent = 60, charging = true)
    private val low = BatteryRingState(percent = 10, charging = false)
    private val ringGreen = 0xFF1DCD3A.toInt()

    @Test fun parsesRgbAndArgbAndRejectsGarbage() {
        assertEquals(0xFFFFFFFF.toInt(), parseBatteryColor("#FFFFFF"))
        assertEquals(0x80123456.toInt(), parseBatteryColor(" #80123456 "))
        assertEquals(0xFF00FF00.toInt(), parseBatteryColor("00ff00"))
        assertNull(parseBatteryColor(""))
        assertNull(parseBatteryColor("#12345"))
        assertNull(parseBatteryColor("#GGGGGG"))
    }

    @Test fun formatRoundTripsThroughParse() {
        val argb = 0xCC336699.toInt()
        assertEquals("#CC336699", formatBatteryColor(argb))
        assertEquals(argb, parseBatteryColor(formatBatteryColor(argb)))
    }

    @Test fun unsetPercentColorFollowsRingInEveryState() {
        // 默认（升级上来的老用户）：数字三态都没设，必须和环同色，观感不变。
        val percent = BatteryColorOverrides()
        for (state in listOf(idle, charging, low)) {
            assertNull(percent.overrideFor(state))
            assertEquals(ringGreen, percent.resolve(state, ringGreen))
        }
    }

    @Test fun invalidPercentColorFallsBackToRing() {
        val percent = BatteryColorOverrides(idle = "nope", charging = "#12", low = "#XYZXYZ")
        for (state in listOf(idle, charging, low)) {
            assertEquals(ringGreen, percent.resolve(state, ringGreen))
        }
    }

    @Test fun percentOverrideAppliesOnlyToItsOwnState() {
        val percent = BatteryColorOverrides(charging = "#FFFFFFFF")
        assertEquals(0xFFFFFFFF.toInt(), percent.resolve(charging, ringGreen))
        assertEquals(ringGreen, percent.resolve(idle, ringGreen))
        assertEquals(ringGreen, percent.resolve(low, ringGreen))
    }

    @Test fun chargingWinsOverLowWhenPickingTheSlot() {
        val overrides = BatteryColorOverrides(idle = "#111111", charging = "#222222", low = "#333333")
        val chargingAndLow = BatteryRingState(percent = 5, charging = true)
        assertEquals(0xFF222222.toInt(), overrides.overrideFor(chargingAndLow))
        assertEquals(0xFF333333.toInt(), overrides.overrideFor(low))
        assertEquals(0xFF111111.toInt(), overrides.overrideFor(idle))
        // 阈值是用户可调的：同一个电量在更低阈值下就不算低电量。
        assertEquals(0xFF111111.toInt(), overrides.overrideFor(BatteryRingState(10, false, lowThreshold = 5)))
    }
}
