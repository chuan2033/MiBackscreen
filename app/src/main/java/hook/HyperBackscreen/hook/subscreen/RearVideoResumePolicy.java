package hook.HyperBackscreen.hook.subscreen;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 背屏视频壁纸续播的纯逻辑：进度快照的 key、续播位置的计算、LRU 快照表、模板归零是否要拦。
 * 不碰任何 Android / 宿主类，单元测试直接覆盖。
 *
 * <p>快照 key = 「上次打开的路径」+「#」+「时长」。时长用来防撞：不同壁纸可能用同一个相对路径但内容不同。
 * 重新打开播放器时还拿不到新播放器的时长，所以按路径取最近一条快照，prepare 完成后再用真实时长核对
 * （{@link #durationMatches}），对不上就撤销恢复。
 */
public final class RearVideoResumePolicy {
    /** MAML TextureVideoView / BaseVideoView 的播放状态取值（与宿主常量一致）。 */
    public static final int STATE_PREPARED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_PAUSED = 4;
    public static final int STATE_PLAYBACK_COMPLETED = 5;

    /** 快照表上限：背屏同一时刻只有一张壁纸，留几条给「切走又切回」与多视频模板。 */
    public static final int MAX_SNAPSHOTS = 8;
    /** 小于这个位置不值得 seek（重新解码首帧比直接从头播更慢）。 */
    public static final int MIN_SEEK_MS = 200;
    /** 非循环视频离结尾不足这个时长视为已经播完，按模板原意从头开始。 */
    public static final int END_GUARD_MS = 300;
    /** prepare 后核对时长允许的误差。同一个文件两次打开的时长是确定值，留一点余量给解码器取整。 */
    public static final int DURATION_TOLERANCE_MS = 50;
    /** 外部命令执行线程拿不到时的兜底时间窗：最近一次 resume / exitAod 下发后这么久内仍视为生命周期命令。 */
    public static final long LIFECYCLE_COMMAND_WINDOW_MS = 1500L;

    private RearVideoResumePolicy() {
    }

    /** 记录快照时机的播放状态是否可信（已 prepare 且没出错）。 */
    public static boolean isSnapshotState(int state) {
        return state == STATE_PREPARED || state == STATE_PLAYING
                || state == STATE_PAUSED || state == STATE_PLAYBACK_COMPLETED;
    }

    /** 快照 key；路径为空时返回 null（没法认领，不记）。 */
    @Nullable
    public static String key(@Nullable String path, int durationMs) {
        if (path == null || path.isEmpty()) return null;
        return path + "#" + Math.max(durationMs, 0);
    }

    /**
     * 重开播放器时该从哪里开始。
     *
     * <ul>
     *   <li>循环视频且时长已知：取模，避免 seek 到时长之外；</li>
     *   <li>非循环视频已播完（状态是 COMPLETED 或离结尾不足 {@link #END_GUARD_MS}）：回到 0，保持「每次亮屏播一遍」；</li>
     *   <li>其余：停在哪里就从哪里继续（快照不随墙钟推进）。</li>
     * </ul>
     * 结果小于 {@link #MIN_SEEK_MS} 一律当 0。
     */
    public static int resumeTarget(int positionMs, int durationMs, boolean looping, boolean completed) {
        if (positionMs <= 0) return 0;
        int target;
        if (looping && durationMs > 0) {
            target = positionMs % durationMs;
        } else if (!looping && (completed || (durationMs > 0 && positionMs >= durationMs - END_GUARD_MS))) {
            target = 0;
        } else {
            target = positionMs;
        }
        return target < MIN_SEEK_MS ? 0 : target;
    }

    /** prepare 之后的真实时长与快照时长是否是同一个视频。快照时长未知（≤0）时不作为否决理由。 */
    public static boolean durationMatches(int snapshotDurationMs, int actualDurationMs) {
        if (snapshotDurationMs <= 0 || actualDurationMs <= 0) return true;
        return Math.abs(snapshotDurationMs - actualDurationMs) <= DURATION_TOLERANCE_MS;
    }

    /**
     * 模板在 resume / exitAod 里显式 {@code seekTo 0} 时是否吞掉。
     *
     * @param requestedMs     模板要 seek 的位置，只拦 0
     * @param command         当前线程正在执行的外部命令（可能为 null）
     * @param msSinceLifecycle 距离最近一次 resume / exitAod 下发的毫秒数；没有记录时传负数
     * @param hasPendingRestore 该视频是否有一次尚未完成的续播
     * @param pausedMidway    该视频是否正停在中途（暂停且位置 &gt; {@link #MIN_SEEK_MS}）
     */
    public static boolean shouldSwallowSeekZero(
            int requestedMs,
            @Nullable String command,
            long msSinceLifecycle,
            boolean hasPendingRestore,
            boolean pausedMidway
    ) {
        if (requestedMs != 0) return false;
        if (!hasPendingRestore && !pausedMidway) return false;
        if (isLifecycleCommand(command)) return true;
        // 带 delay 的命令会被 post 出去，执行时已不在 onAction 里，用时间窗兜底。
        return command == null && msSinceLifecycle >= 0 && msSinceLifecycle <= LIFECYCLE_COMMAND_WINDOW_MS;
    }

    /** 亮屏 / 退出息屏时背屏中心下发给壁纸的外部命令。enterEdit / exitEdit、用户点击不算。 */
    public static boolean isLifecycleCommand(@Nullable String command) {
        return "resume".equals(command) || "exitAod".equals(command);
    }

    /** 一条进度快照：在哪个位置、视频多长、恢复时该 seek 到哪里。 */
    public static final class Snapshot {
        public final String path;
        public final int durationMs;
        public final int targetMs;

        public Snapshot(@NonNull String path, int durationMs, int targetMs) {
            this.path = path;
            this.durationMs = durationMs;
            this.targetMs = targetMs;
        }
    }

    /**
     * 进程级 LRU 快照表（按访问顺序，最多 {@link #MAX_SNAPSHOTS} 条）。所有方法加锁：
     * 写入可能来自 MAML 渲染线程（VideoCommand config）或主线程（Surface 回调）。
     */
    public static final class SnapshotStore {
        private final int capacity;
        private final LinkedHashMap<String, Snapshot> entries;

        public SnapshotStore() {
            this(MAX_SNAPSHOTS);
        }

        public SnapshotStore(int capacity) {
            this.capacity = Math.max(1, capacity);
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                    return size() > SnapshotStore.this.capacity;
                }
            };
        }

        public synchronized void put(@NonNull Snapshot snapshot) {
            String key = key(snapshot.path, snapshot.durationMs);
            if (key == null) return;
            entries.remove(key);
            entries.put(key, snapshot);
        }

        /** 取该路径最近一次记下的快照并从表里拿走（一次续播只用一次，下一次 release 会重新记）。 */
        @Nullable
        public synchronized Snapshot takeLatestForPath(@Nullable String path) {
            if (path == null || path.isEmpty()) return null;
            String prefix = path + "#";
            String latestKey = null;
            for (String key : entries.keySet()) {
                if (key.startsWith(prefix)) latestKey = key;
            }
            return latestKey == null ? null : entries.remove(latestKey);
        }

        public synchronized void remove(@NonNull String path, int durationMs) {
            String key = key(path, durationMs);
            if (key != null) entries.remove(key);
        }

        public synchronized void clear() {
            entries.clear();
        }

        public synchronized int size() {
            return entries.size();
        }

        /** 由旧到新的 key 列表，测试与诊断用。 */
        @NonNull
        public synchronized List<String> keys() {
            List<String> keys = new ArrayList<>(entries.size());
            for (Iterator<String> it = entries.keySet().iterator(); it.hasNext(); ) {
                keys.add(it.next());
            }
            return keys;
        }
    }
}
