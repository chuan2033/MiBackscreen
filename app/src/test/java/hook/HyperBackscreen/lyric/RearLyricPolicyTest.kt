package hook.HyperBackscreen.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearLyricPolicyTest {
    private val line = LyricLineUpdate(LyricSession("com.netease.cloudmusic", "song-1"), "晴天的风")
    private val card = LyricCardState(
        enabled = true,
        cardPackage = "com.netease.cloudmusic",
        cardPlaying = true,
        cardVisible = true,
        launcherResumed = true,
        inAod = false,
    )

    @Test fun showsLineWhenEveryConditionHolds() {
        assertEquals("晴天的风", RearLyricPolicy.lyricToShow(line, card))
    }

    @Test fun restoresArtistWhenAnyConditionFails() {
        assertNull(RearLyricPolicy.lyricToShow(line, card.copy(enabled = false)))
        assertNull(RearLyricPolicy.lyricToShow(null, card))
        assertNull(RearLyricPolicy.lyricToShow(line, card.copy(cardPlaying = false)))
        assertNull(RearLyricPolicy.lyricToShow(line, card.copy(cardVisible = false)))
        assertNull(RearLyricPolicy.lyricToShow(line, card.copy(launcherResumed = false)))
        assertNull(RearLyricPolicy.lyricToShow(line, card.copy(inAod = true)))
        assertNull(RearLyricPolicy.lyricToShow(line.copy(paused = true), card))
        assertNull(RearLyricPolicy.lyricToShow(line.copy(text = "  "), card))
    }

    @Test fun lyricFromAnotherAppNeverLandsOnThisCard() {
        assertNull(RearLyricPolicy.lyricToShow(line, card.copy(cardPackage = "com.tencent.qqmusic")))
        assertNull(RearLyricPolicy.lyricToShow(line, card.copy(cardPackage = null)))
        assertNull(RearLyricPolicy.lyricToShow(line.copy(session = LyricSession(null, "song-1")), card))
    }

    @Test fun normalizeKeepsFirstNonBlankLineTrimmed() {
        assertEquals("原文", RearLyricPolicy.normalize("  原文 \r\n翻译"))
        assertEquals("第二行", RearLyricPolicy.normalize("\n  \r第二行"))
        assertNull(RearLyricPolicy.normalize(""))
        assertNull(RearLyricPolicy.normalize(null))
    }

    @Test fun stopOnlyEndsMatchingOrUnknownSessions() {
        val current = LyricSession("a.music", "1")
        assertTrue(current.acceptsStop(LyricSession("a.music", "1")))
        assertTrue(current.acceptsStop(LyricSession(null, null)))
        assertTrue(current.acceptsStop(LyricSession("a.music", null)))
        assertFalse(current.acceptsStop(LyricSession("b.music", "1")))
        assertFalse(current.acceptsStop(LyricSession("a.music", "2")))
        assertTrue(LyricSession(null, null).acceptsStop(LyricSession("b.music", "9")))
    }

    @Test fun backendPrefersV4ThenLegacyAndNeverBoth() {
        assertEquals(SuperLyricBackend.V4, SuperLyricBackend.select { Any() })
        assertEquals(
            SuperLyricBackend.LEGACY,
            SuperLyricBackend.select { if (it == SuperLyricServices.LEGACY) Any() else null },
        )
        assertEquals(SuperLyricBackend.V4,
            SuperLyricBackend.select { if (it == SuperLyricServices.V4) Any() else null })
        assertEquals(SuperLyricBackend.NONE, SuperLyricBackend.select { null })
    }

    @Test fun backendLookupFailureCountsAsMissing() {
        assertEquals(
            SuperLyricBackend.LEGACY,
            SuperLyricBackend.select { if (it == SuperLyricServices.V4) throw SecurityException() else Any() },
        )
    }

    @Test fun availabilityPrefersLiveServiceOverPackageState() {
        assertEquals(SuperLyricAvailability.LEGACY, SuperLyricAvailability.from(false, SuperLyricBackend.LEGACY))
        assertEquals(SuperLyricAvailability.V4, SuperLyricAvailability.from(true, SuperLyricBackend.V4))
        assertEquals(
            SuperLyricAvailability.SERVICE_UNAVAILABLE,
            SuperLyricAvailability.from(true, SuperLyricBackend.NONE),
        )
        assertEquals(SuperLyricAvailability.NOT_INSTALLED, SuperLyricAvailability.from(false, SuperLyricBackend.NONE))
    }
}
