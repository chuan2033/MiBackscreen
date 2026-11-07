package hook.HyperBackscreen.ui.function

import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionFeatureCountTest {
    @Test fun allOffCountsZero() {
        assertEquals(0, countEnabledFunctions(false, false, false, false, false, false, false, false))
    }

    @Test fun allOnCountsEightFunctions() {
        assertEquals(8, countEnabledFunctions(true, true, true, true, true, true, true, true))
    }

    @Test fun everySwitchCombinationCountsEachFunctionExactlyOnce() {
        for (mask in 0 until 256) {
            val enabled = List(8) { mask and (1 shl it) != 0 }
            assertEquals("mask=$mask", Integer.bitCount(mask), countEnabledFunctions(
                disableLongPress = enabled[0],
                removeWallpaperLimit = enabled[1],
                removeAppCardLimit = enabled[2],
                fixRearScreenApply = enabled[3],
                disableRearScreenCover = enabled[4],
                disableDoubleTapWake = enabled[5],
                enable18ProFeatures = enabled[6],
                enableBatteryRing = enabled[7],
            ))
        }
    }

    @Test fun doubleTapMasterCountsAsOneWithoutCountingItsSelectedPackages() {
        assertEquals(1, countEnabledFunctions(false, false, false, false, false, true, false, false))
    }

    @Test fun automaticWallpaperRotationCountsAsOneFunction() {
        assertEquals(
            1,
            countEnabledFunctions(
                disableLongPress = false,
                removeWallpaperLimit = false,
                removeAppCardLimit = false,
                fixRearScreenApply = false,
                disableRearScreenCover = false,
                disableDoubleTapWake = false,
                enable18ProFeatures = false,
                autoWallpaperEnabled = true,
            ),
        )
    }

    @Test fun rearMediaSwitchesCountInTotalsAndTheirGroups() {
        val media = RearMediaSwitches(
            videoResume = true,
            videoUnlockLimits = true,
            videoKeepAudio = true,
            rearLyric = true,
        )
        assertEquals(
            4,
            countEnabledFunctions(false, false, false, false, false, false, false, false, rearMedia = media),
        )
        val groups = countFunctionGroups(
            disableRearScreenCover = false,
            disableDoubleTapWake = false,
            enableBatteryRing = false,
            batteryRingSupported = true,
            disableLongPress = false,
            removeAppCardLimit = false,
            autoWallpaperEnabled = false,
            rearScreenOffTimeoutActive = false,
            keepRearScreenOnInGame = false,
            fixRearScreenApply = false,
            faceEnrollmentCompat = false,
            removeWallpaperLimit = false,
            rearMedia = media,
        )
        // 视频三项显示在「主题壁纸」组，歌词在「背屏」组。
        assertEquals(FunctionGroupCounts(system = 0, subscreen = 1, theme = 3), groups)
    }
}
