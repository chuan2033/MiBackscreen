package hook.HyperBackscreen.ui.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearDisplayIdParserTest {
    private val displayDump = """
        |    mBaseDisplayInfo=DisplayInfo{"内置屏幕", displayId 0, displayGroupId 0, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_TRUSTED, real 1220 x 2656, largest app 2656 x 2656, smallest app 1220 x 1220, appVsyncOff 1000000, presDeadline 2666666, mode 1, defaultMode 1, modes [{id=1, width=1220, height=2656, fps=120.0}], hdrCapabilities null, rotation 0, state ON, type INTERNAL, uniqueId "local:4630946457447247251", app 1220 x 2656, density 520}
        |    mOverrideDisplayInfo=DisplayInfo{"内置屏幕", displayId 0, displayGroupId 0, type INTERNAL, uniqueId "local:4630946457447247251", app 1220 x 2547}
        |    mBaseDisplayInfo=DisplayInfo{"内置屏幕", displayId 1, displayGroupId 1, FLAG_TRUSTED, real 976 x 596, rotation 0, state OFF, type INTERNAL, uniqueId "local:4630946457447247252", app 976 x 596, density 320}
        |    mBaseDisplayInfo=DisplayInfo{"Overlay #1", displayId 2, displayGroupId 0, type OVERLAY, uniqueId "overlay:1", app 720 x 480}
        """.trimMargin()

    private val surfaceFlingerDump = """
        |Display 4630946457447247251 (HWC display 0): port=147 pnpId=QCM displayName="primary"
        |Display 4630946457447247252 (HWC display 1): port=148 pnpId=QCM displayName="secondary"
        """.trimMargin()

    @Test fun logicalDisplayOneMapsToItsLocalUniqueId() {
        val ids = RearDisplayIdParser.resolve(displayDump, null)
        assertEquals(RearDisplayIds(1, "4630946457447247252"), ids)
    }

    @Test fun mappingIgnoresNonLocalDisplaysAndDisplayGroupIds() {
        val mapping = RearDisplayIdParser.parseLogicalMapping(displayDump)
        assertEquals(mapOf(0 to "4630946457447247251", 1 to "4630946457447247252"), mapping)
    }

    @Test fun fallsBackToSurfaceFlingerExcludingPrimary() {
        val onlyPrimary = displayDump.lines().filter { "displayId 0" in it }.joinToString("\n")
        assertEquals(
            RearDisplayIds(1, "4630946457447247252"),
            RearDisplayIdParser.resolve(onlyPrimary, surfaceFlingerDump),
        )
    }

    @Test fun surfaceFlingerOnlyFallbackDropsHwcZero() {
        assertEquals(
            RearDisplayIds(1, "4630946457447247252"),
            RearDisplayIdParser.resolve("", surfaceFlingerDump),
        )
    }

    @Test fun ambiguousOrMissingOutputFails() {
        assertNull(RearDisplayIdParser.resolve("", null))
        assertNull(RearDisplayIdParser.resolve("garbage", "nothing here"))
        val threeDisplays = surfaceFlingerDump +
            "\nDisplay 4630946457447247253 (HWC display 2): port=149 displayName=\"x\""
        assertNull(RearDisplayIdParser.resolve("", threeDisplays))
    }

    @Test fun recordScriptUsesBothIdsAndSignalChecksProcessName() {
        val script = RearRecordScripts.record(RearDisplayIds(1, "4630946457447247252"))
        assertTrue(script.contains("screenrecord --display-id 4630946457447247252 --time-limit 180 "))
        assertTrue(script.contains("input -d 1 keyevent KEYCODE_WAKEUP"))
        assertTrue(script.contains("echo $$\n"))
        val signal = RearRecordScripts.signal(1234, "INT")
        assertTrue(signal.contains("/proc/1234/comm"))
        assertTrue(signal.contains("kill -INT 1234"))
        assertFalse(signal.contains("-9"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun recordScriptRejectsNonNumericPhysicalId() {
        RearRecordScripts.record(RearDisplayIds(1, "1; rm -rf /"))
    }

    @Test fun durationFormatting() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65))
        assertEquals("03:00", formatDuration(180))
    }
}
