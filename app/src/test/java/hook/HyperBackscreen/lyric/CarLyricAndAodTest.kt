package hook.HyperBackscreen.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLyricAndAodTest {
    private val timeline = LyricTimeline.of(
        listOf(
            TimedLine("词：周杰伦", 0, 1000),
            TimedLine("融化成最甜蜜的糖", 1000, 3000),
            TimedLine("陪你看到天亮", 3000, 6000),
        ),
        // SuperLyric 服务端会用元数据标题覆盖歌名：车载模式下这里本身就是一句歌词。
        "词：周杰伦",
    )!!
    private val line = LyricLineUpdate(
        LyricSession("com.tencent.qqmusic", "qq:1"),
        "陪你看到天亮",
        title = "词：周杰伦",
        timeline = timeline,
    )
    private val carMode = AlbumText("融化成最甜蜜的糖", "April Encounter-Linkitty", "April Encounter")
    private val qq = "com.tencent.qqmusic"

    @Test fun splitsCarArtistFieldUsingKnownTitle() {
        assertEquals(
            AlbumText("April Encounter", "Linkitty", "April Encounter"),
            RearLyricPolicy.restoreCarLyricAlbum(carMode, line, qq, "April Encounter"),
        )
    }

    @Test fun titleWithDashNeedsMatchingCandidate() {
        val album = carMode.copy(artist = "Re-Birth-A-Lin")
        assertEquals(
            AlbumText("Re-Birth", "A-Lin", "April Encounter"),
            RearLyricPolicy.restoreCarLyricAlbum(album, line, qq, "Re-Birth"),
        )
        // 候选对不上、又有多个「-」：拆不出来就不改。
        assertNull(RearLyricPolicy.restoreCarLyricAlbum(album, line, qq, null))
    }

    @Test fun ignoresWrongRememberedTitleThatDoesNotPrefixArtist() {
        // 切歌那一刻误记下了歌词首行「大眠 - 王心凌 (Cyndi Wang)」：对不上前缀，退回到按唯一的「-」拆。
        val album = AlbumText("融化成最甜蜜的糖", "大眠-王心凌", "CYNDILOVES2SING")
        assertEquals(
            AlbumText("大眠", "王心凌", "CYNDILOVES2SING"),
            RearLyricPolicy.restoreCarLyricAlbum(album, line, qq, "大眠 - 王心凌 (Cyndi Wang)"),
        )
    }

    @Test fun neverUsesALyricLineAsTheRealTitle() {
        // SuperLyric 给的歌名是歌词「词：周杰伦」：不拿它当前缀；这里歌手字段只有一个「-」仍能拆。
        val album = carMode.copy(artist = "词：周杰伦-Someone-Else")
        assertNull(RearLyricPolicy.restoreCarLyricAlbum(album, line, qq, "词：周杰伦"))
    }

    @Test fun matchesCurrentPushedLineWithoutTimeline() {
        val pushedOnly = line.copy(timeline = null)
        val album = carMode.copy(title = "陪你看到天亮")
        assertEquals("April Encounter", RearLyricPolicy.restoreCarLyricAlbum(album, pushedOnly, qq, null)?.title)
    }

    @Test fun leavesNormalMetadataAlone() {
        val normal = AlbumText("April Encounter", "Linkitty", "April Encounter")
        assertNull(RearLyricPolicy.restoreCarLyricAlbum(normal, line, qq, "April Encounter"))
        assertNull(RearLyricPolicy.restoreCarLyricAlbum(carMode.copy(title = "下一首"), line, qq, "April Encounter"))
        assertNull(RearLyricPolicy.restoreCarLyricAlbum(carMode, line, "com.netease.cloudmusic", "April Encounter"))
        assertNull(RearLyricPolicy.restoreCarLyricAlbum(carMode, null, qq, "April Encounter"))
        assertNull(RearLyricPolicy.restoreCarLyricAlbum(carMode.copy(artist = "Linkitty"), line, qq, null))
    }

    @Test fun trustsTitleOnlyWhenItIsNotALyric() {
        assertTrue(RearLyricPolicy.isTrustedTitle(AlbumText("April Encounter", "Linkitty", null), line))
        assertFalse(RearLyricPolicy.isTrustedTitle(carMode, line))
        // 还没收到歌词：只认「歌手 = 标题-歌手」的车载格式。
        assertTrue(RearLyricPolicy.isTrustedTitle(AlbumText("April Encounter", "April Encounter-Linkitty", null), null))
        assertFalse(RearLyricPolicy.isTrustedTitle(carMode, null))
        assertFalse(RearLyricPolicy.isTrustedTitle(AlbumText("April Encounter", "Linkitty", null), null))
        assertFalse(RearLyricPolicy.isTrustedTitle(AlbumText(" ", "x", null), line))
    }

    @Test fun songLineIsTitleDashArtist() {
        assertEquals("April Encounter - Linkitty", RearLyricPolicy.songLine("April Encounter", "Linkitty", line))
        assertEquals("April Encounter", RearLyricPolicy.songLine("April Encounter", " ", line))
        assertEquals("Linkitty", RearLyricPolicy.songLine(null, "Linkitty", line))
        // 歌名本身是歌词（车载歌词没换回来）：歌手字段已经是「歌名-歌手」，原样用。
        assertEquals("April Encounter-Linkitty", RearLyricPolicy.songLine("陪你看到天亮", "April Encounter-Linkitty", line))
        assertEquals("", RearLyricPolicy.songLine(null, null, null))
    }

    @Test fun aodShowsLyricOnlyWhenOptedIn() {
        val aod = LyricCardState(
            enabled = true,
            cardPackage = "com.tencent.qqmusic",
            cardPlaying = true,
            cardVisible = false,
            launcherResumed = false,
            inAod = true,
        )
        assertNull(RearLyricPolicy.lyricToShow(line, aod))
        assertEquals("陪你看到天亮", RearLyricPolicy.lyricToShow(line, aod.copy(showInAod = true)))
        // 暂停 / 关总开关 / 别的 App 时 AOD 下也不显示。
        assertNull(RearLyricPolicy.lyricToShow(line, aod.copy(showInAod = true, cardPlaying = false)))
        assertNull(RearLyricPolicy.lyricToShow(line, aod.copy(showInAod = true, enabled = false)))
        assertNull(RearLyricPolicy.lyricToShow(line, aod.copy(showInAod = true, cardPackage = "x")))
    }
}
