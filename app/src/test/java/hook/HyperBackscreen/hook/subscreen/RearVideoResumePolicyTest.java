package hook.HyperBackscreen.hook.subscreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class RearVideoResumePolicyTest {
    @Test
    public void keyCombinesPathAndDuration() {
        assertEquals("/tmp/a.mp4#18900", RearVideoResumePolicy.key("/tmp/a.mp4", 18900));
        assertEquals("/tmp/a.mp4#0", RearVideoResumePolicy.key("/tmp/a.mp4", -1));
        assertNull(RearVideoResumePolicy.key("", 100));
        assertNull(RearVideoResumePolicy.key(null, 100));
    }

    @Test
    public void loopingVideoWrapsAroundDuration() {
        assertEquals(1000, RearVideoResumePolicy.resumeTarget(19900, 18900, true, false));
        assertEquals(5000, RearVideoResumePolicy.resumeTarget(5000, 18900, true, false));
        // 取模后太靠前，不值得 seek。
        assertEquals(0, RearVideoResumePolicy.resumeTarget(18950, 18900, true, false));
    }

    @Test
    public void finishedOneShotVideoStartsOver() {
        assertEquals(0, RearVideoResumePolicy.resumeTarget(6000, 6000, false, true));
        assertEquals(0, RearVideoResumePolicy.resumeTarget(5800, 6000, false, false));
        assertEquals(4000, RearVideoResumePolicy.resumeTarget(4000, 6000, false, false));
    }

    @Test
    public void unknownDurationKeepsPositionAndTinyPositionsAreIgnored() {
        assertEquals(3000, RearVideoResumePolicy.resumeTarget(3000, -1, true, false));
        assertEquals(0, RearVideoResumePolicy.resumeTarget(150, 6000, false, false));
        assertEquals(0, RearVideoResumePolicy.resumeTarget(0, 6000, true, false));
    }

    @Test
    public void durationCheckToleratesRoundingOnly() {
        assertTrue(RearVideoResumePolicy.durationMatches(18900, 18930));
        assertFalse(RearVideoResumePolicy.durationMatches(18900, 6000));
        assertTrue(RearVideoResumePolicy.durationMatches(-1, 6000));
    }

    @Test
    public void storeEvictsLeastRecentlyUsedBeyondCapacity() {
        RearVideoResumePolicy.SnapshotStore store = new RearVideoResumePolicy.SnapshotStore(3);
        store.put(new RearVideoResumePolicy.Snapshot("/a", 10_000, 1000));
        store.put(new RearVideoResumePolicy.Snapshot("/b", 10_000, 1000));
        store.put(new RearVideoResumePolicy.Snapshot("/c", 10_000, 1000));
        store.put(new RearVideoResumePolicy.Snapshot("/a", 10_000, 2000)); // 刷新 /a
        store.put(new RearVideoResumePolicy.Snapshot("/d", 10_000, 1000));
        assertEquals(Arrays.asList("/c#10000", "/a#10000", "/d#10000"), store.keys());
        assertEquals(3, store.size());
    }

    @Test
    public void defaultCapacityIsEight() {
        RearVideoResumePolicy.SnapshotStore store = new RearVideoResumePolicy.SnapshotStore();
        for (int i = 0; i < 12; i++) {
            store.put(new RearVideoResumePolicy.Snapshot("/v" + i, 1000, 500));
        }
        assertEquals(RearVideoResumePolicy.MAX_SNAPSHOTS, store.size());
        assertNull(store.takeLatestForPath("/v0"));
        assertNotNull(store.takeLatestForPath("/v11"));
    }

    @Test
    public void takeReturnsNewestEntryForPathAndRemovesIt() {
        RearVideoResumePolicy.SnapshotStore store = new RearVideoResumePolicy.SnapshotStore();
        store.put(new RearVideoResumePolicy.Snapshot("/same", 6000, 1000));
        store.put(new RearVideoResumePolicy.Snapshot("/other", 6000, 1000));
        store.put(new RearVideoResumePolicy.Snapshot("/same", 9000, 4000));
        RearVideoResumePolicy.Snapshot taken = store.takeLatestForPath("/same");
        assertNotNull(taken);
        assertEquals(9000, taken.durationMs);
        assertEquals(4000, taken.targetMs);
        // 路径前缀不能误配：/same 不应取到 /same2。
        store.put(new RearVideoResumePolicy.Snapshot("/same2", 6000, 1000));
        RearVideoResumePolicy.Snapshot older = store.takeLatestForPath("/same");
        assertNotNull(older);
        assertEquals(6000, older.durationMs);
        assertNull(store.takeLatestForPath("/same"));
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    public void seekZeroIsSwallowedOnlyDuringLifecycleCommandsWithSomethingToKeep() {
        assertTrue(RearVideoResumePolicy.shouldSwallowSeekZero(0, "resume", -1, true, false));
        assertTrue(RearVideoResumePolicy.shouldSwallowSeekZero(0, "exitAod", -1, false, true));
        // 编辑、用户点击触发的归零不拦。
        assertFalse(RearVideoResumePolicy.shouldSwallowSeekZero(0, "exitEdit", 100, true, true));
        assertFalse(RearVideoResumePolicy.shouldSwallowSeekZero(0, "enterEdit", -1, true, true));
        // 已播完 / 没有续播：模板的 replay 照常。
        assertFalse(RearVideoResumePolicy.shouldSwallowSeekZero(0, "resume", -1, false, false));
        // 非 0 的 seek 一律放行。
        assertFalse(RearVideoResumePolicy.shouldSwallowSeekZero(500, "resume", -1, true, true));
    }

    @Test
    public void delayedCommandFallsBackToTimeWindow() {
        assertTrue(RearVideoResumePolicy.shouldSwallowSeekZero(0, null, 800, true, false));
        assertFalse(RearVideoResumePolicy.shouldSwallowSeekZero(0, null, 5000, true, false));
        assertFalse(RearVideoResumePolicy.shouldSwallowSeekZero(0, null, -1, true, false));
    }
}
