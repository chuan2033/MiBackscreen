package hook.HyperBackscreen.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricTimelineTest {
    private val timeline = LyricTimeline.of(
        listOf(
            TimedLine("你若成风 - 许嵩", 400, 2000),
            TimedLine("词：许嵩", 2000, 2500),
            TimedLine("你若化成风", 7000, 9400),
        ),
        "你若成风",
    )!!
    private val pushedFirst = LyricLineUpdate(
        LyricSession("com.tencent.qqmusic", "qq:1"),
        "你若成风 - 许嵩",
        title = "你若成风",
        timeline = timeline,
    )

    @Test fun indexAtUsesLastStartedLine() {
        assertEquals(-1, timeline.indexAt(0))
        assertEquals(0, timeline.indexAt(400))
        assertEquals(1, timeline.indexAt(6999))
        assertEquals(2, timeline.indexAt(100_000))
    }

    @Test fun timelineAdvancesWithoutPerLinePushes() {
        // 发布方只推了全量歌词（停在第一句），进度到了第三句：按时间轴显示第三句。
        val resolved = RearLyricPolicy.resolveLine(pushedFirst, 8000, "你若成风")
        assertEquals("你若化成风", resolved.text)
    }

    @Test fun leadSwitchesSlightlyEarly() {
        assertEquals("你若化成风", RearLyricPolicy.resolveLine(pushedFirst, 6900, "你若成风").text)
        assertEquals("词：许嵩", RearLyricPolicy.resolveLine(pushedFirst, 6800, "你若成风").text)
    }

    @Test fun beforeFirstLineShowsNothing() {
        assertNull(RearLyricPolicy.resolveLine(pushedFirst, 0, "你若成风").text)
    }

    @Test fun fallsBackToPushedTextWithoutPositionOrOnTitleMismatch() {
        assertEquals("你若成风 - 许嵩", RearLyricPolicy.resolveLine(pushedFirst, null, "你若成风").text)
        assertEquals("你若成风 - 许嵩", RearLyricPolicy.resolveLine(pushedFirst, -1, "你若成风").text)
        // 卡片已经换到下一首、时间轴还是上一首的：不用时间轴。
        assertEquals("你若成风 - 许嵩", RearLyricPolicy.resolveLine(pushedFirst, 8000, "七月七日晴").text)
        // 卡片歌名未知：照常用时间轴。
        assertEquals("你若化成风", RearLyricPolicy.resolveLine(pushedFirst, 8000, null).text)
    }

    @Test fun lyricInMetadataTitleStillCountsAsSameSong() {
        // 播放器开了车载 / 蓝牙歌词，元数据标题是当前这句歌词。
        assertEquals("你若化成风", RearLyricPolicy.resolveLine(pushedFirst, 8000, "词：许嵩").text)
        assertEquals("你若化成风", RearLyricPolicy.resolveLine(pushedFirst, 8000, " 你若化成风 ").text)
    }

    @Test fun pulledLineGetsTitleFromTimelineForMatching() {
        val pulled = pushedFirst.copy(session = LyricSession(null, "qq:1"), title = null)
        val resolved = RearLyricPolicy.resolveLine(pulled, 8000, "你若成风")
        assertEquals("你若成风", resolved.title)
        val card = LyricCardState(true, "com.tencent.qqmusic", true, true, true, false, "你若成风")
        assertEquals("你若化成风", RearLyricPolicy.lyricToShow(resolved, card))
    }

    @Test fun nextDelayTargetsNextLineStart() {
        // 3000ms 时（加提前量 3150）下一句在 7000：还要 3850ms，超过上限按上限。
        assertEquals(1500L, RearLyricPolicy.nextTimelineDelay(pushedFirst, 3000, 1f, null, 1500))
        assertEquals(350L, RearLyricPolicy.nextTimelineDelay(pushedFirst, 6500, 1f, null, 1500))
        assertEquals(175L, RearLyricPolicy.nextTimelineDelay(pushedFirst, 6500, 2f, null, 1500))
        // 最后一句之后：按上限定期校正（可能被拖回去）。
        assertEquals(1500L, RearLyricPolicy.nextTimelineDelay(pushedFirst, 50_000, 1f, null, 1500))
        assertNull(RearLyricPolicy.nextTimelineDelay(pushedFirst.copy(timeline = null), 3000, 1f, null, 1500))
        assertNull(RearLyricPolicy.nextTimelineDelay(pushedFirst, null, 1f, null, 1500))
    }

    @Test fun rejectsTimelineWithoutUsableTimestamps() {
        assertNull(LyricTimeline.of(listOf(TimedLine("a", 0, 0)), null))
        assertNull(LyricTimeline.of(listOf(TimedLine("a", 0, 0), TimedLine("b", 0, 0)), null))
        assertNull(LyricTimeline.of(null, null))
        assertNotNull(LyricTimeline.of(listOf(TimedLine("b", 900, 0), TimedLine("a", 100, 0)), null))
    }
}
