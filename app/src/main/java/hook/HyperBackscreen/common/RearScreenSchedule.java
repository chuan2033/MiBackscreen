package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;

/**
 * 「定时关闭背屏」的时间窗换算。
 *
 * 只做纯计算，不读偏好、不碰系统接口：偏好值和当前时刻都由调用方传进来，
 * 这样 UI、Hook 端和单测可以用同一份判断。
 *
 * 时间值统一是 {@code "HH:mm"}，内部换算成「当天第几分钟」。时间窗允许跨零点
 * （23:00 → 07:00），起止相同时按「没有窗口」处理 —— 否则会被理解成全天关闭。
 */
public final class RearScreenSchedule {
    public static final String DEFAULT_START = "23:00";
    public static final String DEFAULT_END = "07:00";
    /**
     * 结束边界后的宽限分钟数，用于判断「当前是否刚离开时间窗不久」。
     *
     * 纯计算辅助：给上层判断「刚过结束点」这类场景用，本身不驱动任何唤醒逻辑。
     */
    public static final int WAKE_SLACK_MINUTES = 3;

    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    private RearScreenSchedule() {
    }

    /** 当前时刻的「当天第几分钟」，跟随系统时区，每次现算（不做缓存）。 */
    public static int nowMinutes() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR
                + calendar.get(Calendar.MINUTE);
    }

    /**
     * 把 {@code "HH:mm"} 解析成「当天第几分钟」；格式不符返回 [fallback]。
     * 时与分都要求两位，避免 "7:5" 这类写法被当成合法值。
     */
    public static int parseMinutes(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator != trimmed.length() - 3) return fallback;
        String hourText = trimmed.substring(0, separator);
        String minuteText = trimmed.substring(separator + 1);
        if (!isTwoDigits(hourText) || !isTwoDigits(minuteText)) return fallback;
        int hour = Integer.parseInt(hourText);
        int minute = Integer.parseInt(minuteText);
        if (hour > 23 || minute > 59) return fallback;
        return hour * MINUTES_PER_HOUR + minute;
    }

    /** 「当天第几分钟」格式化成 {@code "HH:mm"}；越界值按 0 点处理。 */
    @NonNull
    public static String format(int minutes) {
        int normalized = minutes;
        if (normalized < 0 || normalized >= MINUTES_PER_DAY) normalized = 0;
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                normalized / MINUTES_PER_HOUR, normalized % MINUTES_PER_HOUR);
    }

    /** 规范化一个用户输入的时间串：解析失败时回落到 [fallback] 对应的规范写法。 */
    @NonNull
    public static String normalize(@Nullable String value, @NonNull String fallback) {
        int fallbackMinutes = parseMinutes(fallback, 0);
        return format(parseMinutes(value, fallbackMinutes));
    }

    /**
     * [nowMinutes] 是否落在 [start] → [end] 的时间窗内。
     *
     * 任一端解析失败、或起止相同，都返回 false —— 宁可不生效，也不要误判成「全天不亮」。
     */
    public static boolean isActive(
            @Nullable String start,
            @Nullable String end,
            int nowMinutes
    ) {
        int startMinutes = parseMinutes(start, -1);
        int endMinutes = parseMinutes(end, -1);
        if (startMinutes < 0 || endMinutes < 0 || startMinutes == endMinutes) return false;
        if (startMinutes < endMinutes) {
            return nowMinutes >= startMinutes && nowMinutes < endMinutes;
        }
        // 跨零点：23:00 → 07:00 表示 >= 23:00 或 < 07:00。
        return nowMinutes >= startMinutes || nowMinutes < endMinutes;
    }

    /**
     * 当前是否刚离开时间窗不久（结束边界后的 [WAKE_SLACK_MINUTES] 分钟内）。
     *
     * 纯计算辅助：窗口内、或结束超过宽限后才被发现时都返回 false。
     */
    public static boolean isWithinWakeSlack(
            @Nullable String start,
            @Nullable String end,
            int nowMinutes
    ) {
        int startMinutes = parseMinutes(start, -1);
        int endMinutes = parseMinutes(end, -1);
        if (startMinutes < 0 || endMinutes < 0 || startMinutes == endMinutes) return false;
        int sinceEnd = (nowMinutes - endMinutes + MINUTES_PER_DAY) % MINUTES_PER_DAY;
        return sinceEnd <= WAKE_SLACK_MINUTES;
    }

    private static boolean isTwoDigits(@NonNull String text) {
        if (text.length() != 2) return false;
        return Character.isDigit(text.charAt(0)) && Character.isDigit(text.charAt(1));
    }
}
