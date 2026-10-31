package hook.HyperBackscreen.hook;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Pure interval and index rules shared by the host scheduler and unit tests. */
public final class AutoWallpaperRotationPolicy {
    public static final int OFF = 0;
    /** 按列表顺序循环；默认值，保持升级前后行为一致。 */
    public static final String ORDER_SEQUENTIAL = "sequential";
    /** 每次从「除当前以外」的候选里随机挑一张。 */
    public static final String ORDER_RANDOM = "random";
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private AutoWallpaperRotationPolicy() {
    }

    public static int parseIntervalMinutes(@Nullable String raw) {
        if (raw == null) return OFF;
        try {
            return normalizeIntervalMinutes(Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return OFF;
        }
    }

    public static int normalizeIntervalMinutes(int minutes) {
        return minutes == 1 || minutes == 5 || minutes == 15 ? minutes : OFF;
    }

    @NonNull
    public static String normalizePreferenceValue(@Nullable String raw) {
        return String.valueOf(parseIntervalMinutes(raw));
    }

    public static int nextIndex(int currentIndex, int itemCount) {
        if (itemCount < 2 || currentIndex < 0 || currentIndex >= itemCount) return -1;
        return (currentIndex + 1) % itemCount;
    }

    @NonNull
    public static String normalizeOrder(@Nullable String raw) {
        return ORDER_RANDOM.equals(raw) ? ORDER_RANDOM : ORDER_SEQUENTIAL;
    }

    /**
     * 按 [order] 挑下一张。随机模式会排除当前这张，所以候选数是 {@code itemCount - 1}，
     * 保证相邻两次不会停在同图（否则用户会觉得「没换」）。
     * [random] 由调用方复用同一个实例；传 null 时退回顺序。
     */
    public static int nextIndex(
            int currentIndex,
            int itemCount,
            @Nullable String order,
            @Nullable java.util.Random random
    ) {
        if (itemCount < 2 || currentIndex < 0 || currentIndex >= itemCount) return -1;
        if (!ORDER_RANDOM.equals(order) || random == null) return nextIndex(currentIndex, itemCount);
        int candidate = random.nextInt(itemCount - 1);
        // 把 [0, itemCount-1) 的随机数映射到「跳过 currentIndex」后的槽位。
        return candidate >= currentIndex ? candidate + 1 : candidate;
    }

    public static long intervalMillis(int minutes) {
        return (long) normalizeIntervalMinutes(minutes) * MILLIS_PER_MINUTE;
    }

    public static long remainingDelayMillis(
            long lastSwitchElapsed,
            long nowElapsed,
            int minutes
    ) {
        long interval = intervalMillis(minutes);
        if (interval <= 0L) return Long.MAX_VALUE;
        long elapsed = Math.max(0L, nowElapsed - lastSwitchElapsed);
        return Math.max(0L, interval - elapsed);
    }
}
