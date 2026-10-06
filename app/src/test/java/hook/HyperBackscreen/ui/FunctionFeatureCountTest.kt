package hook.HyperBackscreen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionFeatureCountTest {
    @Test fun allOffCountsZero() {
        assertEquals(0, countEnabledFunctions(false, false, false, false, false, false, false, false))
    }

    @Test fun allOnCountsNineFunctions() {
        assertEquals(9, countEnabledFunctions(true, true, true, true, true, true, true, true, true))
    }

    @Test fun everySwitchCombinationCountsEachFunctionExactlyOnce() {
        for (mask in 0 until 512) {
            val enabled = List(9) { mask and (1 shl it) != 0 }
            assertEquals("mask=$mask", Integer.bitCount(mask), countEnabledFunctions(
                disableLongPress = enabled[0],
                removeWallpaperLimit = enabled[1],
                removeAppCardLimit = enabled[2],
                fixRearScreenApply = enabled[3],
                disableRearScreenCover = enabled[4],
                disableDoubleTapWake = enabled[5],
                enablePickup = enabled[6],
                enable18ProFeatures = enabled[7],
                enableBatteryRing = enabled[8],
            ))
        }
    }

    @Test fun doubleTapMasterCountsAsOneWithoutCountingItsSelectedPackages() {
        assertEquals(1, countEnabledFunctions(false, false, false, false, false, true, false, false))
    }
}
