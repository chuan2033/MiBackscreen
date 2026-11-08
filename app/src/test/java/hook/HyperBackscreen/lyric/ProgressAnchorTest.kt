package hook.HyperBackscreen.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressAnchorTest {
    @Test fun playingAnchorAdvancesWithElapsedTimeAndSpeed() {
        val anchor = ProgressAnchor(position = 10_000, speed = 1f, elapsedAt = 5_000, playing = true)
        assertEquals(10_000, anchor.positionAt(5_000))
        assertEquals(12_500, anchor.positionAt(7_500))
        val fast = anchor.copy(speed = 2f)
        assertEquals(15_000, fast.positionAt(7_500))
    }

    @Test fun pausedAnchorStaysPut() {
        val paused = ProgressAnchor(position = 10_000, speed = 1f, elapsedAt = 5_000, playing = false)
        assertEquals(10_000, paused.positionAt(60_000))
    }

    @Test fun unknownSpeedWhilePlayingCountsAsNormalSpeed() {
        val unknown = ProgressAnchor(position = 10_000, speed = 0f, elapsedAt = 5_000, playing = true)
        assertEquals(12_000, unknown.positionAt(7_000))
    }

    @Test fun freshestSnapshotWins() {
        // 切歌：卡片的 MediaController 还停在上一首 65s，v4 帧已经是新歌开头。
        val stale = ProgressAnchor(position = 65_000, speed = 1f, elapsedAt = 1_000, playing = true)
        val fresh = ProgressAnchor(position = 0, speed = 1f, elapsedAt = 1_050, playing = true)
        assertEquals(fresh, ProgressAnchor.freshest(stale, fresh))
        assertEquals(fresh, ProgressAnchor.freshest(fresh, stale))
        assertEquals(stale, ProgressAnchor.freshest(stale, null))
        assertEquals(fresh, ProgressAnchor.freshest(null, fresh))
        assertEquals(null, ProgressAnchor.freshest(null, null))
    }

    @Test fun clockGoingBackwardsNeverRewindsBelowSnapshot() {
        val anchor = ProgressAnchor(position = 10_000, speed = 1f, elapsedAt = 5_000, playing = true)
        assertEquals(10_000, anchor.positionAt(4_000))
    }

    @Test fun anchorDrivesTimelineWhenCardHasNoProgress() {
        val timeline = LyricTimeline.of(
            listOf(TimedLine("第一句", 0, 3000), TimedLine("第二句", 3000, 6000), TimedLine("第三句", 6000, 9000)),
            "歌",
        )!!
        val anchor = ProgressAnchor(position = 1_000, speed = 1f, elapsedAt = 100, playing = true)
        val line = LyricLineUpdate(LyricSession("pkg", "id"), "第一句", title = "歌", timeline = timeline, anchor = anchor)
        // 服务端停在第一句（不逐句推送）：按快照外推，5s 后进度到 6000 → 第三句。
        assertEquals("第三句", RearLyricPolicy.resolveLine(line, anchor.positionAt(5_100), "歌").text)
        assertEquals("第二句", RearLyricPolicy.resolveLine(line, anchor.positionAt(2_100), "歌").text)
    }
}
