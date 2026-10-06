package hook.HyperBackscreen.ui.battery

import org.junit.Assert.*
import org.junit.Test

class BatteryRingStateTest {
    @Test fun eachBatteryPercentIsPreserved() {
        for (percent in 0..100) {
            assertEquals(percent, BatteryRingState.from(percent, 100, 3, false)!!.percent)
        }
        assertEquals(51, BatteryRingState.from(102, 200, 2, true)!!.percent)
    }

    @Test fun chargingTakesPriorityOverLowBattery() {
        for (percent in 0..100) {
            assertEquals("status_bar_battery_charging", BatteryRingState.from(percent, 100, 2, true)!!.colorRole)
        }
    }

    @Test fun lowBatteryThresholdIsStrictlyBelowTwenty() {
        assertEquals("status_bar_battery_low", BatteryRingState.from(19, 100, 3, false)!!.colorRole)
        assertEquals("status_bar_battery_level_white", BatteryRingState.from(20, 100, 3, false)!!.colorRole)
    }

    @Test fun pluggedFullIsGreenAndPausedChargeUsesBatteryLevel() {
        assertTrue(BatteryRingState.from(100, 100, 5, true)!!.charging)
        assertFalse(BatteryRingState.from(100, 100, 5, false)!!.charging)
        assertFalse(BatteryRingState.from(80, 100, 4, true)!!.charging)
    }

    @Test fun malformedBatteryDataIsHidden() {
        assertNull(BatteryRingState.from(-1, 100, 2, true))
        assertNull(BatteryRingState.from(50, 0, 2, true))
        assertNull(BatteryRingState.from(101, 100, 2, true))
    }
}
