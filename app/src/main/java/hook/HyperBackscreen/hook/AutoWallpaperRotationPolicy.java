package hook.HyperBackscreen.hook;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Pure interval and index rules shared by the host scheduler and unit tests. */
public final class AutoWallpaperRotationPolicy {
    public static final int OFF = 0;
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
