package hook.HyperBackscreen.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import hook.HyperBackscreen.app.ModuleApp;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearScreenSchedule;
import hook.HyperBackscreen.common.RearScreenWakeMatcher;
import hook.HyperBackscreen.hook.subscreen.AutoWallpaperRotationPolicy;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.service.XposedService;

public final class PrefsBridge {
    private static final String TAG = Constants.LOG_TAG + ":PrefsBridge";
    private static final String PENDING_PANEL_PREFIX = "__pending_panel__";
    static final String PENDING_UI_PREFIX = "__pending_ui__";
    /**
     * 宿主进程里刚写成功的值。RemotePreferences 的变更回传有延迟，面板 / 电量环 / 长按拦截
     * 在这段时间内先读这里，避免显示或执行旧值。只保留 [OVERRIDE_TTL_MS]：过期后一律回到
     * 远程偏好，否则之后在主 App 里的修改会被这份旧值一直顶掉（直到宿主进程重启）。
     */
    private static final long OVERRIDE_TTL_MS = 5_000L;

    private static final class TimedValue {
        final Object value;
        final long at;

        TimedValue(Object value) {
            this.value = value;
            this.at = android.os.SystemClock.elapsedRealtime();
        }

        boolean expired() {
            return android.os.SystemClock.elapsedRealtime() - at > OVERRIDE_TTL_MS;
        }
    }

    private static final ConcurrentHashMap<String, TimedValue> sRemoteBooleanOverrides =
            new ConcurrentHashMap<>();

    @Nullable
    private static Object liveOverride(@NonNull ConcurrentHashMap<String, TimedValue> map, @NonNull String key) {
        TimedValue timed = map.get(key);
        if (timed == null) return null;
        if (timed.expired()) {
            map.remove(key, timed);
            return null;
        }
        return timed.value;
    }

    /**
     * 远程偏好的写入统一放到这条单线程队列：不在主线程做 Binder，按提交顺序落盘；
     * 本地先记下待同步值，提交成功才清除，失败或进程被杀时由重连后的补交兜底。
     */
    private static final java.util.concurrent.ExecutorService REMOTE_WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "MiBackscreen-prefs");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 等待此前排队的远程写入全部完成（最多 [timeoutMs]）。重启宿主作用域前调用，
     * 确保宿主重启后读到的是新值。不要在主线程调用。
     */
    public static boolean awaitRemoteWrites(long timeoutMs) {
        try {
            REMOTE_WRITER.submit(() -> { }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 只存在 App 本地、不进远程偏好的界面设置。备份 / 恢复时必须直接读写本地。 */
    private static final java.util.Set<String> LOCAL_ONLY_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            Constants.KEY_CHECK_UPDATES,
            Constants.KEY_FLOATING_NAV_BAR,
            Constants.KEY_LIQUID_GLASS,
            Constants.KEY_BOTTOM_BAR_STYLE,
            Constants.KEY_BOTTOM_BAR_BLUR,
            Constants.KEY_SHOW_FUNCTION_COUNT));

    public static final boolean DEFAULT_DISABLE_LONG_PRESS_EDIT = true;
    public static final boolean DEFAULT_REMOVE_WALLPAPER_LIMIT = true;
    public static final boolean DEFAULT_ENABLE_APP_CARD = true;
    public static final boolean DEFAULT_ENABLE_18_PRO_FEATURES = true;
    public static final boolean DEFAULT_ENABLE_BATTERY_RING = false;
    /** 背屏电量显示颜色覆盖，空字符串表示跟随系统（SystemUI 电池资源色）。 */
    public static final String DEFAULT_BATTERY_COLOR = "";
    public static final String DEFAULT_BATTERY_PERCENT_POSITION = "off";
    /**
     * 电量位置微调，单位：模组尺寸的百分比，在预设锚点上叠加。
     *
     * 水平偏移左右各存一份（`_left` / `_right` 两个键），默认值跟着「数字电量」的位置预设走
     * （右侧 +10%、左侧 -10%），没法压成一个常量，所以空串表示「该侧没调过，按该侧默认来」，
     * 由 `BatteryRingLayout.percentOffsetX(position, read)` 落地；下面这个键是历史遗留的单槽，
     * 只在两个分侧键都为空时当迁移回退读一次，不再写入。
     * 垂直默认 0，表示留在模组垂直中心。
     */
    public static final String DEFAULT_BATTERY_PERCENT_OFFSET_X = "";
    public static final String DEFAULT_BATTERY_PERCENT_OFFSET_Y = "0";
    /** 电量线条缩放，单位：百分比；100 表示严格贴模组轮廓。 */
    public static final String DEFAULT_BATTERY_RING_SCALE = "100";
    /** 电量线条笔画粗细，单位：背屏像素。 */
    public static final String DEFAULT_BATTERY_RING_STROKE = "10";
    /** 百分比数字字号，单位：背屏像素。 */
    public static final String DEFAULT_BATTERY_PERCENT_TEXT_SIZE = "38";
    /** 百分比数字字重：400 常规 / 700 加粗，默认 700 保持旧版观感。 */
    public static final String DEFAULT_BATTERY_PERCENT_TEXT_WEIGHT = "700";
    /** 未充部分的底轨，默认不画；"1" 开 / "0" 关。 */
    public static final String DEFAULT_BATTERY_RING_TRACK = "0";
    /** 线条样式，默认连体轮廓（contour）；另一个取值是上下双环（twin）。 */
    public static final String DEFAULT_BATTERY_RING_STYLE = "contour";
    /** 低电量阈值（百分比），默认 20 与系统电池图标换色点一致。 */
    public static final String DEFAULT_BATTERY_LOW_THRESHOLD = "20";
    public static final String DEFAULT_BATTERY_CHARGE_SWEEP = "1";
    public static final String DEFAULT_BATTERY_PERCENT_SHADOW = "1";
    public static final String DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES = "0";
    /** 定时切换的挑图方式，默认顺序循环。 */
    public static final String DEFAULT_AUTO_WALLPAPER_ORDER =
            AutoWallpaperRotationPolicy.ORDER_SEQUENTIAL;
    /** 背屏息屏超时覆盖；"0" = 跟随系统，其余为毫秒值。 */
    public static final String DEFAULT_REAR_SCREEN_OFF_TIMEOUT = "0";
    /** 定时关闭背屏：默认关，避免老用户升级后夜里背屏突然不亮。 */
    public static final boolean DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED = false;
    public static final String DEFAULT_REAR_SCREEN_SCHEDULE_START = RearScreenSchedule.DEFAULT_START;
    public static final String DEFAULT_REAR_SCREEN_SCHEDULE_END = RearScreenSchedule.DEFAULT_END;
    /** 时间窗内形态：默认「关闭背屏」（黑屏）；`doze` 是停在息屏。 */
    public static final String DEFAULT_REAR_SCREEN_SCHEDULE_MODE = Constants.DEFAULT_REAR_SCREEN_SCHEDULE_MODE;
    public static final boolean DEFAULT_REMOVE_APP_CARD_LIMIT = true;
    public static final boolean DEFAULT_FIX_REAR_SCREEN_APPLY = false;
    /** 主题商店人脸录入计数兼容；默认关，与旧版「修复背屏壁纸应用失败」默认一致。 */
    public static final boolean DEFAULT_FACE_ENROLLMENT_COMPAT = false;
    /** 游戏（主屏高负载）时保持背屏亮；默认关 —— 开启会让游戏的功耗/发热优化失效。 */
    public static final boolean DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME = false;
    private static final boolean DEFAULT_FLOATING_NAV_BAR = false;
    private static final boolean DEFAULT_LIQUID_GLASS = false;
    /** 三种底栏共用的背景模糊开关；默认开，保持与旧版观感一致。 */
    private static final boolean DEFAULT_BOTTOM_BAR_BLUR = true;
    private static final boolean DEFAULT_SHOW_FUNCTION_COUNT = true;
    /** 启动时自动检查 GitHub Release 是否有新版；默认开。 */
    private static final boolean DEFAULT_CHECK_UPDATES = true;
    public static final boolean DEFAULT_DISABLE_REAR_SCREEN_COVER = false;
    public static final boolean DEFAULT_DISABLE_DOUBLE_TAP_WAKE = false;
    public static final String DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES = "";
    /** 背屏音乐控件白名单默认空：完全沿用背屏中心内置（云控）名单。 */
    public static final String DEFAULT_MUSIC_WIDGET_WHITELIST_PACKAGES = "";
    /** 背屏小部件默认不加任何页面。 */
    public static final String DEFAULT_REAR_APP_WIDGETS = "";
    /** 按应用禁用背屏双击熄屏的名单默认空：系统原逻辑不变。 */
    public static final String DEFAULT_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES = "";
    public static final boolean DEFAULT_THEME_SETTINGS_SHORTCUT = true;
    /** 系统设置里的模块入口默认不加：要把系统设置加进作用域，由用户主动选位置。 */
    public static final String DEFAULT_SETTINGS_ENTRY = "off";
    /** 背屏视频壁纸三项增强默认全关：会改变官方壁纸「每次亮屏重播」的设计、放开导入限制、让背屏出声。 */
    public static final boolean DEFAULT_REAR_VIDEO_RESUME = false;
    public static final boolean DEFAULT_REAR_VIDEO_UNLOCK_LIMITS = false;
    public static final boolean DEFAULT_REAR_VIDEO_KEEP_AUDIO = false;
    /** 背屏实时歌词默认关：依赖第三方模块 SuperLyric。 */
    public static final boolean DEFAULT_ENABLE_REAR_LYRIC = false;
    /** AOD 时也显示歌词默认关：息屏期间每句歌词都会刷新一次背屏，略费电。 */
    public static final boolean DEFAULT_REAR_LYRIC_IN_AOD = false;

    /**
     * 配置备份覆盖的偏好键：只收「会改变行为或观感」的键。
     * 一次性请求（app_card_install_request）与只读的历史回退键（battery_percent_offset_x）不收。
     * 值统一按字符串备份，恢复时按这里的类型解析；文件里缺的键保持当前值。
     */
    private static final Map<String, Boolean> BACKUP_BOOLEAN_DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> BACKUP_STRING_DEFAULTS = new LinkedHashMap<>();

    static {
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_ENABLE_18_PRO_FEATURES, DEFAULT_ENABLE_18_PRO_FEATURES);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_ENABLE_APP_CARD, DEFAULT_ENABLE_APP_CARD);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_REMOVE_APP_CARD_LIMIT, DEFAULT_REMOVE_APP_CARD_LIMIT);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_ENABLE_BATTERY_RING, DEFAULT_ENABLE_BATTERY_RING);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_FACE_ENROLLMENT_COMPAT, DEFAULT_FACE_ENROLLMENT_COMPAT);
        BACKUP_BOOLEAN_DEFAULTS.put(
                Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME,
                DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME);
        BACKUP_BOOLEAN_DEFAULTS.put(
                Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED,
                DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_DISABLE_REAR_SCREEN_COVER, DEFAULT_DISABLE_REAR_SCREEN_COVER);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, DEFAULT_DISABLE_DOUBLE_TAP_WAKE);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_REAR_VIDEO_RESUME, DEFAULT_REAR_VIDEO_RESUME);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_REAR_VIDEO_UNLOCK_LIMITS, DEFAULT_REAR_VIDEO_UNLOCK_LIMITS);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_REAR_VIDEO_KEEP_AUDIO, DEFAULT_REAR_VIDEO_KEEP_AUDIO);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_ENABLE_REAR_LYRIC, DEFAULT_ENABLE_REAR_LYRIC);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_REAR_LYRIC_IN_AOD, DEFAULT_REAR_LYRIC_IN_AOD);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_CHECK_UPDATES, DEFAULT_CHECK_UPDATES);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_FLOATING_NAV_BAR, DEFAULT_FLOATING_NAV_BAR);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_LIQUID_GLASS, DEFAULT_LIQUID_GLASS);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_BOTTOM_BAR_BLUR, DEFAULT_BOTTOM_BAR_BLUR);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_SHOW_FUNCTION_COUNT, DEFAULT_SHOW_FUNCTION_COUNT);

        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_COLOR_IDLE, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_COLOR_CHARGING, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_COLOR_LOW, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_COLOR_IDLE, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_COLOR_LOW, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_POSITION, DEFAULT_BATTERY_PERCENT_POSITION);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT, DEFAULT_BATTERY_PERCENT_OFFSET_X);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT, DEFAULT_BATTERY_PERCENT_OFFSET_X);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_OFFSET_Y, DEFAULT_BATTERY_PERCENT_OFFSET_Y);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_RING_SCALE, DEFAULT_BATTERY_RING_SCALE);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_RING_STROKE, DEFAULT_BATTERY_RING_STROKE);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_TEXT_SIZE, DEFAULT_BATTERY_PERCENT_TEXT_SIZE);
        BACKUP_STRING_DEFAULTS.put(
                Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT,
                DEFAULT_BATTERY_PERCENT_TEXT_WEIGHT);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_RING_TRACK, DEFAULT_BATTERY_RING_TRACK);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_RING_STYLE, DEFAULT_BATTERY_RING_STYLE);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_LOW_THRESHOLD, DEFAULT_BATTERY_LOW_THRESHOLD);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_CHARGE_SWEEP, DEFAULT_BATTERY_CHARGE_SWEEP);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_PERCENT_SHADOW, DEFAULT_BATTERY_PERCENT_SHADOW);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES, DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_AUTO_WALLPAPER_ORDER, DEFAULT_AUTO_WALLPAPER_ORDER);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_REAR_SCREEN_OFF_TIMEOUT, DEFAULT_REAR_SCREEN_OFF_TIMEOUT);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_SETTINGS_ENTRY, DEFAULT_SETTINGS_ENTRY);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BOTTOM_BAR_STYLE, "standard");
        BACKUP_STRING_DEFAULTS.put(
                Constants.KEY_REAR_SCREEN_SCHEDULE_START,
                DEFAULT_REAR_SCREEN_SCHEDULE_START);
        BACKUP_STRING_DEFAULTS.put(
                Constants.KEY_REAR_SCREEN_SCHEDULE_END,
                DEFAULT_REAR_SCREEN_SCHEDULE_END);
        BACKUP_STRING_DEFAULTS.put(
                Constants.KEY_REAR_SCREEN_SCHEDULE_MODE,
                DEFAULT_REAR_SCREEN_SCHEDULE_MODE);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES, DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
        BACKUP_STRING_DEFAULTS.put(
                Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES,
                DEFAULT_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES);
        BACKUP_STRING_DEFAULTS.put(
                Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES,
                DEFAULT_MUSIC_WIDGET_WHITELIST_PACKAGES);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_REAR_APP_WIDGETS, DEFAULT_REAR_APP_WIDGETS);
    }

    private PrefsBridge() {
    }

    /** Capture before UI reads refresh the local cache. Never reconcile during diagnostics. */
    @NonNull
    public static String buildSyncDiagnosticReport(@NonNull Context context) {
        XposedService service = ModuleApp.getService();
        java.util.Map<String, ?> localValues;
        try {
            localValues = new java.util.HashMap<>(local(context).getAll());
        } catch (Throwable error) {
            return "local_snapshot_error=" + error.getClass().getSimpleName() + "\n";
        }
        java.util.Map<String, ?> remoteValues = null;
        String remoteError = service == null ? "service_disconnected" : "none";
        try {
            if (service != null) {
                SharedPreferences preferences = service.getRemotePreferences(Constants.PREF_GROUP);
                if (preferences != null) remoteValues = new java.util.HashMap<>(preferences.getAll());
                else remoteError = "preferences_unavailable";
            }
        } catch (Throwable error) {
            remoteError = error.getClass().getSimpleName();
        }
        return "service_connected=" + (service != null)
                + "\nservice_reconciliation_finished=" + ModuleApp.isPreferenceServiceReady()
                + "\nremote_read_error=" + remoteError
                + "\n" + PreferenceSyncReport.build(localValues, remoteValues);
    }

    /** 被 Hook 进程（如背屏）里 ModuleApp 服务通常为 null，此时用 XposedModule 实例取远程偏好。 */
    @Nullable
    private static XposedModule sModule;

    public static void attachModule(@Nullable XposedModule module) {
        sModule = module;
    }

    @NonNull
    private static SharedPreferences local(@NonNull Context context) {
        return context.getSharedPreferences(Constants.PREF_GROUP, Context.MODE_PRIVATE);
    }

    @Nullable
    private static SharedPreferences remote() {
        try {
            XposedService service = ModuleApp.getService();
            if (service != null) {
                return service.getRemotePreferences(Constants.PREF_GROUP);
            }
        } catch (Throwable ignored) {
            // 回落到 XposedModule 实例
        }
        // 被 Hook 进程内 ModuleApp 服务不可用，用 XposedModule 实例的远程偏好（与 Hook 端同源）
        if (sModule != null) {
            try {
                return sModule.getRemotePreferences(Constants.PREF_GROUP);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to get remote prefs via module", e);
            }
        }
        return null;
    }

    /** UI 侧读取：待同步值优先，其次远程并回写缓存，离线时读取本地。 */
    private static boolean readForUi(@NonNull Context context, @NonNull String key, boolean def) {
        return readForUi(local(context), remote(), key, def);
    }

    static boolean readForUi(@NonNull SharedPreferences localPrefs,
                             @Nullable SharedPreferences remote,
                             @NonNull String key, boolean def) {
        String pendingKey = PENDING_UI_PREFIX + key;
        if (localPrefs.contains(pendingKey)) return localPrefs.getBoolean(pendingKey, def);
        if (remote != null) {
            boolean value = remote.getBoolean(key, def);
            localPrefs.edit().putBoolean(key, value).apply();
            return value;
        }
        return localPrefs.getBoolean(key, def);
    }

    public static void setRemoteBooleanOverride(@NonNull String key, boolean value) {
        sRemoteBooleanOverrides.put(key, new TimedValue(value));
    }

    /**
     * 宿主进程里刚由面板写成功的字符串值。RemotePreferences 的变更回传有延迟，
     * 面板与电量环在这段时间内先读这里，避免显示 / 绘制旧值。
     */
    private static final ConcurrentHashMap<String, TimedValue> sRemoteStringOverrides =
            new ConcurrentHashMap<>();

    public static void setRemoteStringOverride(@NonNull String key, @NonNull String value) {
        sRemoteStringOverrides.put(key, new TimedValue(value));
    }

    /** 宿主进程（背屏面板）读字符串偏好：优先刚写成功的覆盖值。 */
    @NonNull
    public static String readStringForRemote(@NonNull String key, @NonNull String def) {
        Object override = liveOverride(sRemoteStringOverrides, key);
        if (override instanceof String) return (String) override;
        SharedPreferences prefs = remotePrefsFromModule();
        if (prefs == null) return def;
        String value = prefs.getString(key, def);
        return value == null ? def : value;
    }

    private static boolean readRemoteBooleanWithOverride(@NonNull String key, boolean def) {
        Object override = liveOverride(sRemoteBooleanOverrides, key);
        if (override instanceof Boolean) return (Boolean) override;
        SharedPreferences prefs = remotePrefsFromModule();
        return prefs != null ? prefs.getBoolean(key, def) : def;
    }

    /** UI 双写；服务未连接时保存待同步值，重连后补交到 RemotePreferences。 */
    private static void writeFromUi(@NonNull Context context, @NonNull String key, boolean value) {
        SharedPreferences localPrefs = local(context);
        // 先记待同步值：远程提交成功才清，服务断开 / 写丢 / 进程被杀时重连后会补交，不会被远程旧值回滚。
        localPrefs.edit()
                .putBoolean(key, value)
                .putBoolean(PENDING_UI_PREFIX + key, value)
                .apply();
        SharedPreferences remote = remote();
        if (remote != null) {
            REMOTE_WRITER.execute(() -> {
                boolean saved;
                try {
                    saved = remote.edit().putBoolean(key, value).commit();
                } catch (Throwable error) {
                    Log.w(TAG, "Remote write failed for " + key, error);
                    saved = false;
                }
                if (saved) clearPendingIfUnchanged(localPrefs, key, value);
            });
        }
        DiagnosticLogStore.recordLocalAsync(context, "preference_write key=" + key
                + " value=" + value + " destination=" + (remote != null ? "remote_commit_queued" : "local_pending"));
    }

    /** 提交成功后清待同步标记；若期间又有新的写入（待同步值已变），保留新的标记。 */
    private static synchronized void clearPendingIfUnchanged(@NonNull SharedPreferences localPrefs,
                                                             @NonNull String key,
                                                             @NonNull Object value) {
        String pendingKey = PENDING_UI_PREFIX + key;
        Object pending = localPrefs.getAll().get(pendingKey);
        if (value.equals(pending)) localPrefs.edit().remove(pendingKey).apply();
    }

    private static boolean writeFromUiCommitted(@NonNull Context context, @NonNull String key, boolean value) {
        SharedPreferences localPrefs = local(context);
        SharedPreferences remote = remote();
        boolean saved = false;
        if (remote != null) {
            saved = remote.edit().putBoolean(key, value).commit();
            SharedPreferences.Editor editor = localPrefs.edit().putBoolean(key, value);
            if (saved) {
                editor.remove(PENDING_UI_PREFIX + key);
            } else {
                editor.putBoolean(PENDING_UI_PREFIX + key, value);
            }
            editor.apply();
        } else {
            localPrefs.edit()
                    .putBoolean(key, value)
                    .putBoolean(PENDING_UI_PREFIX + key, value)
                    .apply();
        }
        DiagnosticLogStore.recordLocalAsync(context, "preference_write key=" + key
                + " value=" + value + " destination=" + (saved ? "remote_commit" : "local_pending"));
        return saved;
    }

    private static String readStringForUi(@NonNull Context context, @NonNull String key, @NonNull String def) {
        return readStringForUi(local(context), remote(), key, def);
    }

    static String readStringForUi(@NonNull SharedPreferences localPrefs,
                                  @Nullable SharedPreferences remote,
                                  @NonNull String key, @NonNull String def) {
        String pendingKey = PENDING_UI_PREFIX + key;
        if (localPrefs.contains(pendingKey)) {
            String value = localPrefs.getString(pendingKey, def);
            return value == null ? def : value;
        }
        if (remote != null) {
            String value = remote.getString(key, def);
            localPrefs.edit().putString(key, value).apply();
            return value == null ? def : value;
        }
        String value = localPrefs.getString(key, def);
        return value == null ? def : value;
    }

    private static void writeStringFromUi(@NonNull Context context, @NonNull String key, @NonNull String value) {
        SharedPreferences localPrefs = local(context);
        localPrefs.edit()
                .putString(key, value)
                .putString(PENDING_UI_PREFIX + key, value)
                .apply();
        SharedPreferences remote = remote();
        if (remote != null) {
            REMOTE_WRITER.execute(() -> {
                boolean saved;
                try {
                    saved = remote.edit().putString(key, value).commit();
                } catch (Throwable error) {
                    Log.w(TAG, "Remote write failed for " + key, error);
                    saved = false;
                }
                if (saved) clearPendingIfUnchanged(localPrefs, key, value);
            });
        }
        DiagnosticLogStore.recordLocalAsync(context, "preference_write key=" + key
                + " destination=" + (remote != null ? "remote_commit_queued" : "local_pending"));
    }

    /**
     * Hook 侧读取：只读 LSPosed 远程偏好——它才是可跨进程共享的真正数据源。
     * 旧实现优先读被 Hook 应用私有目录下的空文件，导致 UI 关闭长按后 Hook 端始终拿默认值。
     */
    private static boolean readForHook(@NonNull XposedModule module, @NonNull String key, boolean def) {
        try {
            SharedPreferences remotePrefs = module.getRemotePreferences(Constants.PREF_GROUP);
            if (remotePrefs != null) {
                return remotePrefs.getBoolean(key, def);
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read " + key, e);
        }
        return def;
    }

    /** 新键存在就用新键；不存在时回退读 legacyKey（只读，不回写），用于拆开关时的平滑迁移。 */
    private static boolean readForHookWithLegacy(@NonNull XposedModule module,
                                                 @NonNull String key,
                                                 @NonNull String legacyKey,
                                                 boolean def) {
        try {
            SharedPreferences remotePrefs = module.getRemotePreferences(Constants.PREF_GROUP);
            if (remotePrefs != null) {
                if (remotePrefs.contains(key)) return remotePrefs.getBoolean(key, def);
                if (remotePrefs.contains(legacyKey)) return remotePrefs.getBoolean(legacyKey, def);
                return def;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read " + key, e);
        }
        return def;
    }

    @NonNull
    private static String readStringForHook(@NonNull XposedModule module, @NonNull String key, @NonNull String def) {
        try {
            SharedPreferences remotePrefs = module.getRemotePreferences(Constants.PREF_GROUP);
            if (remotePrefs != null) {
                String value = remotePrefs.getString(key, def);
                return value == null ? def : value;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read " + key, e);
        }
        return def;
    }

    public static boolean readDisableLongPressForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT);
    }

    public static boolean writeDisableLongPressFromUi(@NonNull Context context, boolean disabled) {
        return writeFromUiCommitted(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, disabled);
    }

    public static boolean readRemoveWallpaperLimitForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
    }

    public static void writeRemoveWallpaperLimitFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT, enabled);
    }

    public static boolean readEnable18ProFeaturesForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_ENABLE_18_PRO_FEATURES, DEFAULT_ENABLE_18_PRO_FEATURES);
    }

    public static boolean readEnableBatteryRingForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_ENABLE_BATTERY_RING, DEFAULT_ENABLE_BATTERY_RING);
    }

    public static boolean writeEnableBatteryRingFromUi(@NonNull Context context, boolean enabled) {
        return writeFromUiCommitted(context, Constants.KEY_ENABLE_BATTERY_RING, enabled);
    }

    public static boolean shouldEnableBatteryRing(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_ENABLE_BATTERY_RING, DEFAULT_ENABLE_BATTERY_RING);
    }

    /**
     * 电量颜色覆盖（UI 侧）。电量环三个键空字符串表示跟随系统；
     * 百分比数字三个键（{@code battery_percent_color_*}）空字符串表示跟随电量环。
     */
    @NonNull
    public static String readBatteryColorForUi(@NonNull Context context, @NonNull String key) {
        return readStringForUi(context, key, DEFAULT_BATTERY_COLOR);
    }

    public static void writeBatteryColorFromUi(@NonNull Context context,
                                               @NonNull String key,
                                               @NonNull String value) {
        writeStringFromUi(context, key, value);
    }

    @NonNull
    public static String readBatteryPercentPositionForUi(@NonNull Context context) {
        return readStringForUi(
                context,
                Constants.KEY_BATTERY_PERCENT_POSITION,
                DEFAULT_BATTERY_PERCENT_POSITION);
    }

    public static void writeBatteryPercentPositionFromUi(@NonNull Context context,
                                                         @NonNull String value) {
        writeStringFromUi(context, Constants.KEY_BATTERY_PERCENT_POSITION, value);
    }

    /** 电量位置 / 线条缩放（UI 侧）；三个键共用一条通道，默认值按 key 决定。 */
    @NonNull
    public static String readBatteryLayoutForUi(@NonNull Context context, @NonNull String key) {
        return readStringForUi(context, key, batteryLayoutDefault(key));
    }

    public static void writeBatteryLayoutFromUi(@NonNull Context context,
                                                @NonNull String key,
                                                @NonNull String value) {
        writeStringFromUi(context, key, value);
    }

    /** 电量颜色覆盖（Hook 侧）：环的键空串跟随系统，数字的键空串跟随电量环。 */
    @NonNull
    public static String readBatteryColor(@NonNull XposedModule module, @NonNull String key) {
        return readStringForHook(module, key, DEFAULT_BATTERY_COLOR);
    }

    @NonNull
    public static String readBatteryPercentPosition(@NonNull XposedModule module) {
        return readStringForHook(
                module,
                Constants.KEY_BATTERY_PERCENT_POSITION,
                DEFAULT_BATTERY_PERCENT_POSITION);
    }

    /** 电量位置 / 线条缩放（Hook 侧）。 */
    @NonNull
    public static String readBatteryLayout(@NonNull XposedModule module, @NonNull String key) {
        return readStringForHook(module, key, batteryLayoutDefault(key));
    }

    @NonNull
    private static String batteryLayoutDefault(@NonNull String key) {
        switch (key) {
            case Constants.KEY_BATTERY_RING_SCALE:
                return DEFAULT_BATTERY_RING_SCALE;
            case Constants.KEY_BATTERY_RING_STROKE:
                return DEFAULT_BATTERY_RING_STROKE;
            case Constants.KEY_BATTERY_PERCENT_TEXT_SIZE:
                return DEFAULT_BATTERY_PERCENT_TEXT_SIZE;
            case Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT:
                return DEFAULT_BATTERY_PERCENT_TEXT_WEIGHT;
            case Constants.KEY_BATTERY_RING_TRACK:
                return DEFAULT_BATTERY_RING_TRACK;
            case Constants.KEY_BATTERY_RING_STYLE:
                return DEFAULT_BATTERY_RING_STYLE;
            case Constants.KEY_BATTERY_LOW_THRESHOLD:
                return DEFAULT_BATTERY_LOW_THRESHOLD;
            case Constants.KEY_BATTERY_CHARGE_SWEEP:
                return DEFAULT_BATTERY_CHARGE_SWEEP;
            case Constants.KEY_BATTERY_PERCENT_SHADOW:
                return DEFAULT_BATTERY_PERCENT_SHADOW;
            case Constants.KEY_BATTERY_PERCENT_OFFSET_Y:
                return DEFAULT_BATTERY_PERCENT_OFFSET_Y;
            default:
                // 水平偏移（含左右分侧键）：空串 = 该侧没调过，按该侧默认值来。
                return DEFAULT_BATTERY_PERCENT_OFFSET_X;
        }
    }

    @NonNull
    public static String readAutoWallpaperIntervalForUi(@NonNull Context context) {
        return AutoWallpaperRotationPolicy.normalizePreferenceValue(readStringForUi(
                context,
                Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES,
                DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES));
    }

    public static void writeAutoWallpaperIntervalFromUi(
            @NonNull Context context,
            @NonNull String value
    ) {
        writeStringFromUi(
                context,
                Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES,
                AutoWallpaperRotationPolicy.normalizePreferenceValue(value));
    }

    public static int readAutoWallpaperIntervalMinutes(@NonNull XposedModule module) {
        return AutoWallpaperRotationPolicy.parseIntervalMinutes(readStringForHook(
                module,
                Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES,
                DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES));
    }

    @NonNull
    public static String readAutoWallpaperOrderForUi(@NonNull Context context) {
        return AutoWallpaperRotationPolicy.normalizeOrder(readStringForUi(
                context,
                Constants.KEY_AUTO_WALLPAPER_ORDER,
                DEFAULT_AUTO_WALLPAPER_ORDER));
    }

    public static void writeAutoWallpaperOrderFromUi(
            @NonNull Context context,
            @NonNull String value
    ) {
        writeStringFromUi(
                context,
                Constants.KEY_AUTO_WALLPAPER_ORDER,
                AutoWallpaperRotationPolicy.normalizeOrder(value));
    }

    /** 挑图方式；宿主每次 tick 现读，切完不用等下一次广播。 */
    @NonNull
    public static String readAutoWallpaperOrder(@NonNull XposedModule module) {
        return AutoWallpaperRotationPolicy.normalizeOrder(readStringForHook(
                module,
                Constants.KEY_AUTO_WALLPAPER_ORDER,
                DEFAULT_AUTO_WALLPAPER_ORDER));
    }

    @NonNull
    public static String readRearScreenOffTimeoutForUi(@NonNull Context context) {
        return normalizeRearScreenOffTimeout(readStringForUi(
                context,
                Constants.KEY_REAR_SCREEN_OFF_TIMEOUT,
                DEFAULT_REAR_SCREEN_OFF_TIMEOUT));
    }

    public static void writeRearScreenOffTimeoutFromUi(
            @NonNull Context context,
            @NonNull String value
    ) {
        writeStringFromUi(
                context,
                Constants.KEY_REAR_SCREEN_OFF_TIMEOUT,
                normalizeRearScreenOffTimeout(value));
    }

    /** 背屏息屏超时覆盖值（毫秒）；0 表示跟随系统。 */
    public static long readRearScreenOffTimeoutMillis(@NonNull XposedModule module) {
        return parseRearScreenOffTimeoutMillis(readStringForHook(
                module,
                Constants.KEY_REAR_SCREEN_OFF_TIMEOUT,
                DEFAULT_REAR_SCREEN_OFF_TIMEOUT));
    }

    @NonNull
    private static String normalizeRearScreenOffTimeout(@Nullable String value) {
        return String.valueOf(parseRearScreenOffTimeoutMillis(value));
    }

    private static long parseRearScreenOffTimeoutMillis(@Nullable String value) {
        if (value == null) return 0L;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return 0L;
        try {
            long millis = Long.parseLong(trimmed);
            if (millis <= 0L) return 0L;
            // 系统设置 subscreen_display_time 是 int，超过 int 上限按上限处理。
            return Math.min(millis, Constants.REAR_SCREEN_OFF_TIMEOUT_ALWAYS_MS);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    public static boolean readRearScreenScheduleEnabledForUi(@NonNull Context context) {
        return readForUi(
                context,
                Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED,
                DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED);
    }

    public static void writeRearScreenScheduleEnabledFromUi(
            @NonNull Context context,
            boolean enabled
    ) {
        writeFromUi(context, Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED, enabled);
    }

    @NonNull
    public static String readRearScreenScheduleStartForUi(@NonNull Context context) {
        return RearScreenSchedule.normalize(readStringForUi(
                context,
                Constants.KEY_REAR_SCREEN_SCHEDULE_START,
                DEFAULT_REAR_SCREEN_SCHEDULE_START), DEFAULT_REAR_SCREEN_SCHEDULE_START);
    }

    public static void writeRearScreenScheduleStartFromUi(
            @NonNull Context context,
            @NonNull String value
    ) {
        writeStringFromUi(
                context,
                Constants.KEY_REAR_SCREEN_SCHEDULE_START,
                RearScreenSchedule.normalize(value, DEFAULT_REAR_SCREEN_SCHEDULE_START));
    }

    @NonNull
    public static String readRearScreenScheduleEndForUi(@NonNull Context context) {
        return RearScreenSchedule.normalize(readStringForUi(
                context,
                Constants.KEY_REAR_SCREEN_SCHEDULE_END,
                DEFAULT_REAR_SCREEN_SCHEDULE_END), DEFAULT_REAR_SCREEN_SCHEDULE_END);
    }

    public static void writeRearScreenScheduleEndFromUi(
            @NonNull Context context,
            @NonNull String value
    ) {
        writeStringFromUi(
                context,
                Constants.KEY_REAR_SCREEN_SCHEDULE_END,
                RearScreenSchedule.normalize(value, DEFAULT_REAR_SCREEN_SCHEDULE_END));
    }

    /**
     * 当前是否处于「定时关闭背屏」的时间窗内。Hook 端每次现算，
     * 不做缓存 —— 时间窗边界必须靠真实时间判断，缓存会让它永远停在上一次的状态。
     */
    public static boolean isRearScreenScheduleActiveNow(@NonNull XposedModule module) {
        if (!readForHook(
                module,
                Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED,
                DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED)) {
            return false;
        }
        return RearScreenSchedule.isActive(
                readStringForHook(
                        module,
                        Constants.KEY_REAR_SCREEN_SCHEDULE_START,
                        DEFAULT_REAR_SCREEN_SCHEDULE_START),
                readStringForHook(
                        module,
                        Constants.KEY_REAR_SCREEN_SCHEDULE_END,
                        DEFAULT_REAR_SCREEN_SCHEDULE_END),
                RearScreenSchedule.nowMinutes());
    }

    @NonNull
    public static String readRearScreenScheduleModeForUi(@NonNull Context context) {
        return normalizeRearScreenScheduleMode(readStringForUi(
                context,
                Constants.KEY_REAR_SCREEN_SCHEDULE_MODE,
                DEFAULT_REAR_SCREEN_SCHEDULE_MODE));
    }

    public static void writeRearScreenScheduleModeFromUi(
            @NonNull Context context,
            @NonNull String value
    ) {
        writeStringFromUi(
                context,
                Constants.KEY_REAR_SCREEN_SCHEDULE_MODE,
                normalizeRearScreenScheduleMode(value));
    }

    /** 时间窗内是否走「关闭背屏」（纯黑）。其余取值（含未知值）按「息屏」处理。 */
    public static boolean shouldForceOffRearScreen(@NonNull XposedModule module) {
        return Constants.REAR_SCREEN_SCHEDULE_MODE_OFF.equals(readStringForHook(
                module,
                Constants.KEY_REAR_SCREEN_SCHEDULE_MODE,
                DEFAULT_REAR_SCREEN_SCHEDULE_MODE));
    }

    @NonNull
    private static String normalizeRearScreenScheduleMode(@Nullable String value) {
        if (Constants.REAR_SCREEN_SCHEDULE_MODE_OFF.equals(value)
                || Constants.REAR_SCREEN_SCHEDULE_MODE_DOZE.equals(value)) {
            return value;
        }
        return DEFAULT_REAR_SCREEN_SCHEDULE_MODE;
    }

    /**
     * 导出会改变行为/观感的偏好（布尔值转成 "true"/"false"，其余保持字符串）。
     * 顺序与 [BACKUP_BOOLEAN_DEFAULTS] / [BACKUP_STRING_DEFAULTS] 一致，方便人工比对。
     */
    @NonNull
    public static Map<String, String> exportPreferencesForBackup(@NonNull Context context) {        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : BACKUP_BOOLEAN_DEFAULTS.entrySet()) {
            // 本地专用的界面设置远程里没有：走远程读会拿到默认值，还会把默认值写回本地。
            boolean value = LOCAL_ONLY_KEYS.contains(entry.getKey())
                    ? local(context).getBoolean(entry.getKey(), entry.getValue())
                    : readForUi(context, entry.getKey(), entry.getValue());
            values.put(entry.getKey(), String.valueOf(value));
        }
        for (Map.Entry<String, String> entry : BACKUP_STRING_DEFAULTS.entrySet()) {
            values.put(entry.getKey(),
                    LOCAL_ONLY_KEYS.contains(entry.getKey())
                            ? readBottomBarStyle(context)
                            : readStringForUi(context, entry.getKey(), entry.getValue()));
        }
        return values;
    }

    /**
     * 把备份里的偏好写回（本地 + 远程双写）。文件里缺的键保持当前值，
     * 所以旧备份不会清掉新版本新增的键。
     *
     * @return 实际写入的键数量
     */
    public static int applyPreferencesFromBackup(
            @NonNull Context context,
            @NonNull Map<String, String> values
    ) {
        int applied = 0;
        for (Map.Entry<String, Boolean> entry : BACKUP_BOOLEAN_DEFAULTS.entrySet()) {
            String raw = values.get(entry.getKey());
            if (raw == null) continue;
            String trimmed = raw.trim();
            boolean value;
            if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
                value = true;
            } else if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
                value = false;
            } else {
                continue; // 不认识的值不写，保持当前设置
            }
            if (LOCAL_ONLY_KEYS.contains(entry.getKey())) {
                local(context).edit().putBoolean(entry.getKey(), value).apply();
            } else {
                writeFromUi(context, entry.getKey(), value);
            }
            applied++;
        }
        for (String key : BACKUP_STRING_DEFAULTS.keySet()) {
            String normalized = normalizeBackupString(key, values.get(key));
            if (normalized == null) continue; // 缺失或非法：保持当前设置，不把脏值写进 Hook
            if (Constants.KEY_BOTTOM_BAR_STYLE.equals(key)) {
                writeBottomBarStyle(context, normalized);
            } else if (LOCAL_ONLY_KEYS.contains(key)) {
                local(context).edit().putString(key, normalized).apply();
            } else {
                writeStringFromUi(context, key, normalized);
            }
            applied++;
        }
        if (!values.containsKey(Constants.KEY_BOTTOM_BAR_STYLE)
                && (values.containsKey(Constants.KEY_FLOATING_NAV_BAR)
                    || values.containsKey(Constants.KEY_LIQUID_GLASS))) {
            writeBottomBarStyle(context,
                    readFloatingNavBar(context)
                            ? (readLiquidGlass(context) ? "liquid" : "floating")
                            : "standard");
        }
        return applied;
    }

    /**
     * 恢复备份时逐键校验：与 UI 写入同一套规范化口径，非法值返回 null（跳过，保持当前值）。
     * 备份文件可能被手改或损坏，不能把 1ms 息屏这类脏值原样写进 Hook。
     */
    @Nullable
    static String normalizeBackupString(@NonNull String key, @Nullable String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        switch (key) {
            case Constants.KEY_BATTERY_COLOR_IDLE:
            case Constants.KEY_BATTERY_COLOR_CHARGING:
            case Constants.KEY_BATTERY_COLOR_LOW:
            case Constants.KEY_BATTERY_PERCENT_COLOR_IDLE:
            case Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING:
            case Constants.KEY_BATTERY_PERCENT_COLOR_LOW:
                return value.isEmpty() || value.matches("#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})") ? value : null;
            case Constants.KEY_BATTERY_PERCENT_POSITION:
                return value.equals("off") || value.equals("left") || value.equals("right") ? value : null;
            case Constants.KEY_BATTERY_RING_TRACK:
            case Constants.KEY_BATTERY_CHARGE_SWEEP:
            case Constants.KEY_BATTERY_PERCENT_SHADOW:
                return value.equals("0") || value.equals("1") ? value : null;
            case Constants.KEY_BATTERY_RING_STYLE:
                return value.equals("contour") || value.equals("twin") ? value : null;
            case Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT:
            case Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT:
                // 空串表示「该侧没调过」，是合法值。
                return value.isEmpty() || value.matches("-?\\d{1,4}") ? value : null;
            case Constants.KEY_BATTERY_PERCENT_OFFSET_Y:
            case Constants.KEY_BATTERY_RING_SCALE:
            case Constants.KEY_BATTERY_RING_STROKE:
            case Constants.KEY_BATTERY_PERCENT_TEXT_SIZE:
            case Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT:
            case Constants.KEY_BATTERY_LOW_THRESHOLD:
                // 数值项读取时会再夹进各自区间，这里只挡掉非数字。
                return value.matches("-?\\d{1,4}") ? value : null;
            case Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES:
            case Constants.KEY_REAR_SCREEN_OFF_TIMEOUT:
                return normalizePanelStringValue(key, value);
            case Constants.KEY_AUTO_WALLPAPER_ORDER:
                return AutoWallpaperRotationPolicy.normalizeOrder(value);
            case Constants.KEY_REAR_SCREEN_SCHEDULE_START:
            case Constants.KEY_REAR_SCREEN_SCHEDULE_END:
                return value.matches("\\d{1,2}:\\d{2}")
                        ? RearScreenSchedule.normalize(value, value)
                        : null;
            case Constants.KEY_REAR_SCREEN_SCHEDULE_MODE:
                return normalizeRearScreenScheduleMode(value);
            case Constants.KEY_SETTINGS_ENTRY:
                return normalizeSettingsEntry(value).equals(value) ? value : null;
            case Constants.KEY_BOTTOM_BAR_STYLE:
                return "standard".equals(value) || "floating".equals(value)
                        || "liquid".equals(value) || "glass".equals(value) ? value : null;
            case Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES:
            case Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES:
            case Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES:
                // 包名列表：按同一套编码重新规范化，非法项直接丢掉。
                return hook.HyperBackscreen.common.PackageListCodec.encode(
                        hook.HyperBackscreen.common.PackageListCodec.parse(value));
            case Constants.KEY_REAR_APP_WIDGETS:
                return hook.HyperBackscreen.common.RearWidgetList.encode(
                        hook.HyperBackscreen.common.RearWidgetList.parse(value));
            default:
                return null;
        }
    }

    public static boolean writeEnable18ProFeaturesFromUi(@NonNull Context context, boolean enabled) {
        try {
            SharedPreferences remotePrefs = remote();
            boolean saved = writeEnable18ProFeatures(local(context), remotePrefs, enabled);
            DiagnosticLogStore.recordLocalAsync(context, "preference_write key=" + Constants.KEY_ENABLE_18_PRO_FEATURES
                    + " value=" + enabled + " saved=" + saved
                    + " destination=" + (remotePrefs != null ? "remote_commit" : "local_pending"));
            return saved;
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to save 18 Pro features", error);
            return false;
        }
    }

    /** Complete the remote write before refreshing hosts that cache their capabilities. */
    @SuppressLint("ApplySharedPref")
    static boolean writeEnable18ProFeatures(@NonNull SharedPreferences localPrefs,
                                            @Nullable SharedPreferences remotePrefs, boolean enabled) {
        String key = Constants.KEY_ENABLE_18_PRO_FEATURES;
        if (remotePrefs == null) {
            return localPrefs.edit().putBoolean(key, enabled)
                    .putBoolean(PENDING_UI_PREFIX + key, enabled).commit();
        }
        if (!remotePrefs.edit().putBoolean(key, enabled).commit()) return false;
        localPrefs.edit().putBoolean(key, enabled).remove(PENDING_UI_PREFIX + key).apply();
        return true;
    }

    public static boolean shouldEnable18ProFeatures(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_ENABLE_18_PRO_FEATURES, DEFAULT_ENABLE_18_PRO_FEATURES);
    }

    public static boolean readEnableAppCardForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_ENABLE_APP_CARD, DEFAULT_ENABLE_APP_CARD);
    }

    public static void writeEnableAppCardFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_ENABLE_APP_CARD, enabled);
    }

    public static boolean requestInstallAppCardFromUi(@NonNull Context context) {
        SharedPreferences localPrefs = local(context);
        SharedPreferences remotePrefs = remote();
        // 每次请求一个新编号：宿主处理过同一编号就不再插入，用户删掉的卡片不会被自动加回来。
        String requestId = String.valueOf(System.currentTimeMillis());
        if (remotePrefs == null) {
            localPrefs.edit()
                    .putBoolean(Constants.KEY_ENABLE_APP_CARD, true)
                    .putBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, true)
                    .putString(Constants.KEY_APP_CARD_INSTALL_REQUEST_ID, requestId)
                    .putBoolean(PENDING_UI_PREFIX + Constants.KEY_ENABLE_APP_CARD, true)
                    .putBoolean(PENDING_UI_PREFIX + Constants.KEY_APP_CARD_INSTALL_REQUEST, true)
                    .putString(PENDING_UI_PREFIX + Constants.KEY_APP_CARD_INSTALL_REQUEST_ID, requestId)
                    .apply();
            DiagnosticLogStore.recordLocalAsync(context,
                    "app_card_install_request destination=local_pending");
            return false;
        }
        boolean saved = remotePrefs.edit()
                .putBoolean(Constants.KEY_ENABLE_APP_CARD, true)
                .putBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, true)
                .putString(Constants.KEY_APP_CARD_INSTALL_REQUEST_ID, requestId)
                .commit();
        SharedPreferences.Editor editor = localPrefs.edit()
                .putBoolean(Constants.KEY_ENABLE_APP_CARD, true)
                .putBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, true)
                .putString(Constants.KEY_APP_CARD_INSTALL_REQUEST_ID, requestId);
        if (saved) {
            editor.remove(PENDING_UI_PREFIX + Constants.KEY_ENABLE_APP_CARD)
                    .remove(PENDING_UI_PREFIX + Constants.KEY_APP_CARD_INSTALL_REQUEST);
        } else {
            editor.putBoolean(PENDING_UI_PREFIX + Constants.KEY_ENABLE_APP_CARD, true)
                    .putBoolean(PENDING_UI_PREFIX + Constants.KEY_APP_CARD_INSTALL_REQUEST, true);
        }
        editor.apply();
        DiagnosticLogStore.recordLocalAsync(context,
                "app_card_install_request destination=" + (saved ? "remote_commit" : "local_pending"));
        return saved;
    }

    public static boolean readRemoveAppCardLimitForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_REMOVE_APP_CARD_LIMIT, DEFAULT_REMOVE_APP_CARD_LIMIT);
    }

    public static void writeRemoveAppCardLimitFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_REMOVE_APP_CARD_LIMIT, enabled);
    }

    public static boolean readFixRearScreenApplyForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
    }

    public static void writeFixRearScreenApplyFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_FIX_REAR_SCREEN_APPLY, enabled);
    }

    /**
     * 人脸兼容原来是「修复背屏壁纸应用失败」的副产物，没有自己的键。
     * 新键从未写过时沿用旧开关的值，避免老用户升级后功能悄悄失效；一旦用户动过新开关，
     * 就以新键为准（旧键不再影响人脸）。
     */
    public static boolean readFaceEnrollmentCompatForUi(@NonNull Context context) {
        SharedPreferences localPrefs = local(context);
        String pendingKey = PENDING_UI_PREFIX + Constants.KEY_FACE_ENROLLMENT_COMPAT;
        if (localPrefs.contains(pendingKey)) {
            return localPrefs.getBoolean(pendingKey, DEFAULT_FACE_ENROLLMENT_COMPAT);
        }
        SharedPreferences remote = remote();
        if (remote != null && remote.contains(Constants.KEY_FACE_ENROLLMENT_COMPAT)) {
            boolean value = remote.getBoolean(
                    Constants.KEY_FACE_ENROLLMENT_COMPAT, DEFAULT_FACE_ENROLLMENT_COMPAT);
            localPrefs.edit()
                    .putBoolean(Constants.KEY_FACE_ENROLLMENT_COMPAT, value)
                    .apply();
            return value;
        }
        if (localPrefs.contains(Constants.KEY_FACE_ENROLLMENT_COMPAT)) {
            return localPrefs.getBoolean(
                    Constants.KEY_FACE_ENROLLMENT_COMPAT, DEFAULT_FACE_ENROLLMENT_COMPAT);
        }
        return readForUi(context, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
    }

    public static void writeFaceEnrollmentCompatFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_FACE_ENROLLMENT_COMPAT, enabled);
    }

    public static boolean readKeepRearScreenOnInGameForUi(@NonNull Context context) {
        return readForUi(
                context,
                Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME,
                DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME);
    }

    public static void writeKeepRearScreenOnInGameFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME, enabled);
    }

    public static boolean readDisableRearScreenCoverForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_DISABLE_REAR_SCREEN_COVER, DEFAULT_DISABLE_REAR_SCREEN_COVER);
    }

    public static void writeDisableRearScreenCoverFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_DISABLE_REAR_SCREEN_COVER, enabled);
    }

    public static boolean readDisableDoubleTapWakeForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, DEFAULT_DISABLE_DOUBLE_TAP_WAKE);
    }

    public static void writeDisableDoubleTapWakeFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, enabled);
    }

    @NonNull
    public static String readDoubleTapWakeDisabledPackagesForUi(@NonNull Context context) {
        return readStringForUi(
                context,
                Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
    }

    public static void writeDoubleTapWakeDisabledPackagesFromUi(@NonNull Context context, @NonNull String packages) {
        writeStringFromUi(context, Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES, packages);
    }

    @NonNull
    public static String readDoubleTapSleepDisabledPackagesForUi(@NonNull Context context) {
        return readStringForUi(
                context,
                Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES,
                DEFAULT_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES);
    }

    public static void writeDoubleTapSleepDisabledPackagesFromUi(@NonNull Context context, @NonNull String packages) {
        writeStringFromUi(context, Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES, packages);
    }

    @NonNull
    public static String readMusicWidgetWhitelistPackagesForUi(@NonNull Context context) {
        return readStringForUi(
                context,
                Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES,
                DEFAULT_MUSIC_WIDGET_WHITELIST_PACKAGES);
    }

    public static void writeMusicWidgetWhitelistPackagesFromUi(@NonNull Context context, @NonNull String packages) {
        writeStringFromUi(context, Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES, packages);
    }

    @NonNull
    public static String readRearAppWidgetsForUi(@NonNull Context context) {
        return readStringForUi(context, Constants.KEY_REAR_APP_WIDGETS, DEFAULT_REAR_APP_WIDGETS);
    }

    public static void writeRearAppWidgetsFromUi(@NonNull Context context, @NonNull String components) {
        writeStringFromUi(context, Constants.KEY_REAR_APP_WIDGETS, components);
    }

    public static boolean readThemeSettingsShortcutForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
    }

    public static void writeThemeSettingsShortcutFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_THEME_SETTINGS_SHORTCUT, enabled);
    }

    @NonNull
    public static String readSettingsEntryForUi(@NonNull Context context) {
        return normalizeSettingsEntry(readStringForUi(context, Constants.KEY_SETTINGS_ENTRY, DEFAULT_SETTINGS_ENTRY));
    }

    public static void writeSettingsEntryFromUi(@NonNull Context context, @NonNull String position) {
        writeStringFromUi(context, Constants.KEY_SETTINGS_ENTRY, normalizeSettingsEntry(position));
    }

    /** 系统设置首页的模块入口位置（off / top / middle / bottom）。 */
    @NonNull
    public static String readSettingsEntryForHook(@NonNull XposedModule module) {
        return normalizeSettingsEntry(readStringForHook(module, Constants.KEY_SETTINGS_ENTRY, DEFAULT_SETTINGS_ENTRY));
    }

    @NonNull
    static String normalizeSettingsEntry(@Nullable String value) {
        if ("top".equals(value) || "middle".equals(value) || "bottom".equals(value)) return value;
        return DEFAULT_SETTINGS_ENTRY;
    }

    public static boolean readRearVideoResumeForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_REAR_VIDEO_RESUME, DEFAULT_REAR_VIDEO_RESUME);
    }

    public static void writeRearVideoResumeFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_REAR_VIDEO_RESUME, enabled);
    }

    public static boolean readRearVideoUnlockLimitsForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_REAR_VIDEO_UNLOCK_LIMITS, DEFAULT_REAR_VIDEO_UNLOCK_LIMITS);
    }

    public static void writeRearVideoUnlockLimitsFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_REAR_VIDEO_UNLOCK_LIMITS, enabled);
    }

    public static boolean readRearVideoKeepAudioForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_REAR_VIDEO_KEEP_AUDIO, DEFAULT_REAR_VIDEO_KEEP_AUDIO);
    }

    public static void writeRearVideoKeepAudioFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_REAR_VIDEO_KEEP_AUDIO, enabled);
    }

    public static boolean readEnableRearLyricForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_ENABLE_REAR_LYRIC, DEFAULT_ENABLE_REAR_LYRIC);
    }

    /**
     * 歌词开关同步提交（Binder，调用方放 IO 线程）：提交成功后才发同步广播，
     * 宿主收到广播时远程偏好已经是新值。失败时新值留作待同步值，重连后补交。
     */
    public static boolean writeEnableRearLyricFromUi(@NonNull Context context, boolean enabled) {
        return writeFromUiCommitted(context, Constants.KEY_ENABLE_REAR_LYRIC, enabled);
    }

    public static boolean readRearLyricInAodForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_REAR_LYRIC_IN_AOD, DEFAULT_REAR_LYRIC_IN_AOD);
    }

    /** 同 {@link #writeEnableRearLyricFromUi}：同步提交，成功后才发同步广播。 */
    public static boolean writeRearLyricInAodFromUi(@NonNull Context context, boolean enabled) {
        return writeFromUiCommitted(context, Constants.KEY_REAR_LYRIC_IN_AOD, enabled);
    }

    /** 纯 UI 外观项，Hook 端不消费，只存本地。 */
    public static boolean readFloatingNavBar(@NonNull Context context) {
        return local(context).getBoolean(Constants.KEY_FLOATING_NAV_BAR, DEFAULT_FLOATING_NAV_BAR);
    }

    public static void writeFloatingNavBar(@NonNull Context context, boolean floating) {
        local(context).edit().putBoolean(Constants.KEY_FLOATING_NAV_BAR, floating).apply();
    }

    public static boolean readLiquidGlass(@NonNull Context context) {
        return local(context).getBoolean(Constants.KEY_LIQUID_GLASS, DEFAULT_LIQUID_GLASS);
    }

    public static void writeLiquidGlass(@NonNull Context context, boolean enabled) {
        local(context).edit().putBoolean(Constants.KEY_LIQUID_GLASS, enabled).apply();
    }

    /** 保存底栏样式，同时维护旧版界面设置的读取结果。 */
    @NonNull
    public static String readBottomBarStyle(@NonNull Context context) {
        SharedPreferences prefs = local(context);
        String stored = prefs.getString(Constants.KEY_BOTTOM_BAR_STYLE, null);
        String resolved = resolveBottomBarStyle(stored,
                prefs.getBoolean(Constants.KEY_FLOATING_NAV_BAR, DEFAULT_FLOATING_NAV_BAR),
                prefs.getBoolean(Constants.KEY_LIQUID_GLASS, DEFAULT_LIQUID_GLASS));
        if (!resolved.equals(stored)) writeBottomBarStyle(context, resolved);
        return resolved;
    }

    @NonNull
    static String resolveBottomBarStyle(@Nullable String stored, boolean floating, boolean liquid) {
        if ("standard".equals(stored) && floating) return liquid ? "liquid" : "floating";
        if ("standard".equals(stored) || "floating".equals(stored)
                || "liquid".equals(stored) || "glass".equals(stored)) return stored;
        if (stored != null || !floating) return "standard";
        return liquid ? "liquid" : "floating";
    }

    public static void writeBottomBarStyle(@NonNull Context context, @NonNull String style) {
        if (!"standard".equals(style) && !"floating".equals(style)
                && !"liquid".equals(style) && !"glass".equals(style)) {
            throw new IllegalArgumentException("Unknown bottom bar style: " + style);
        }
        local(context).edit()
                .putString(Constants.KEY_BOTTOM_BAR_STYLE, style)
                .putBoolean(Constants.KEY_FLOATING_NAV_BAR, !"standard".equals(style))
                .putBoolean(Constants.KEY_LIQUID_GLASS, "liquid".equals(style))
                .commit();
    }

    public static boolean readBottomBarBlur(@NonNull Context context) {
        return local(context).getBoolean(Constants.KEY_BOTTOM_BAR_BLUR, DEFAULT_BOTTOM_BAR_BLUR);
    }

    public static void writeBottomBarBlur(@NonNull Context context, boolean enabled) {
        local(context).edit().putBoolean(Constants.KEY_BOTTOM_BAR_BLUR, enabled).apply();
    }

    public static boolean readShowFunctionCount(@NonNull Context context) {
        return local(context).getBoolean(Constants.KEY_SHOW_FUNCTION_COUNT, DEFAULT_SHOW_FUNCTION_COUNT);
    }

    public static void writeShowFunctionCount(@NonNull Context context, boolean enabled) {
        local(context).edit().putBoolean(Constants.KEY_SHOW_FUNCTION_COUNT, enabled).apply();
    }

    public static boolean readCheckUpdates(@NonNull Context context) {
        return local(context).getBoolean(Constants.KEY_CHECK_UPDATES, DEFAULT_CHECK_UPDATES);
    }

    public static void writeCheckUpdates(@NonNull Context context, boolean enabled) {
        local(context).edit().putBoolean(Constants.KEY_CHECK_UPDATES, enabled).apply();
    }

    public static boolean shouldBlockLongPressEdit(@NonNull XposedModule module) {
        Object override = liveOverride(sRemoteBooleanOverrides, Constants.KEY_DISABLE_LONG_PRESS_EDIT);
        return override instanceof Boolean
                ? (Boolean) override
                : readForHook(module, Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT);
    }

    public static boolean shouldRemoveWallpaperLimit(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
    }

    public static boolean shouldEnableAppCard(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_ENABLE_APP_CARD, DEFAULT_ENABLE_APP_CARD);
    }

    public static boolean shouldInstallAppCard(@NonNull XposedModule module) {
        try {
            SharedPreferences prefs = module.getRemotePreferences(Constants.PREF_GROUP);
            return prefs != null && prefs.getBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, false);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read app card install request", e);
            return false;
        }
    }

    /** 当前安装请求的编号（Hook 侧）；旧版本只有布尔请求，返回空串。 */
    @NonNull
    public static String readAppCardInstallRequestId(@NonNull XposedModule module) {
        return readStringForHook(module, Constants.KEY_APP_CARD_INSTALL_REQUEST_ID, "");
    }

    public static boolean shouldRemoveAppCardLimit(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_REMOVE_APP_CARD_LIMIT, DEFAULT_REMOVE_APP_CARD_LIMIT);
    }

    public static boolean shouldFixRearScreenApply(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
    }

    /** 人脸兼容的 hook 侧读取；新键未设置时回退旧键（见 {@link #readFaceEnrollmentCompatForUi}）。 */
    public static boolean shouldFixFaceEnrollment(@NonNull XposedModule module) {
        return readForHookWithLegacy(
                module,
                Constants.KEY_FACE_ENROLLMENT_COMPAT,
                Constants.KEY_FIX_REAR_SCREEN_APPLY,
                DEFAULT_FACE_ENROLLMENT_COMPAT);
    }

    public static boolean shouldKeepRearScreenOnInGame(@NonNull XposedModule module) {
        return readForHook(
                module,
                Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME,
                DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME);
    }

    public static boolean shouldResumeRearVideo(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_REAR_VIDEO_RESUME, DEFAULT_REAR_VIDEO_RESUME);
    }

    public static boolean shouldUnlockRearVideoLimits(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_REAR_VIDEO_UNLOCK_LIMITS, DEFAULT_REAR_VIDEO_UNLOCK_LIMITS);
    }

    public static boolean shouldKeepRearVideoAudio(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_REAR_VIDEO_KEEP_AUDIO, DEFAULT_REAR_VIDEO_KEEP_AUDIO);
    }

    /** 背屏中心读歌词开关：同步广播刚送到的值优先（远程偏好变更回传有延迟）。 */
    public static boolean shouldShowRearLyric(@NonNull XposedModule module) {
        Object override = liveOverride(sRemoteBooleanOverrides, Constants.KEY_ENABLE_REAR_LYRIC);
        return override instanceof Boolean
                ? (Boolean) override
                : readForHook(module, Constants.KEY_ENABLE_REAR_LYRIC, DEFAULT_ENABLE_REAR_LYRIC);
    }

    /** 背屏中心读「AOD 时也显示歌词」：同步广播刚送到的值优先。 */
    public static boolean shouldShowRearLyricInAod(@NonNull XposedModule module) {
        Object override = liveOverride(sRemoteBooleanOverrides, Constants.KEY_REAR_LYRIC_IN_AOD);
        return override instanceof Boolean
                ? (Boolean) override
                : readForHook(module, Constants.KEY_REAR_LYRIC_IN_AOD, DEFAULT_REAR_LYRIC_IN_AOD);
    }

    public static boolean shouldShowThemeSettingsShortcut(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
    }

    public static boolean shouldDisableRearScreenCover(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_DISABLE_REAR_SCREEN_COVER, DEFAULT_DISABLE_REAR_SCREEN_COVER);
    }

    public static boolean shouldDisableDoubleTapWake(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, DEFAULT_DISABLE_DOUBLE_TAP_WAKE);
    }

    public static boolean shouldSkipDoubleTapWakeForPackages(@NonNull XposedModule module, @Nullable String... packageNames) {
        if (!shouldDisableDoubleTapWake(module)) return false;
        String raw = readStringForHook(
                module,
                Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
        return RearScreenWakeMatcher.matchesAnyPackage(raw, packageNames);
    }

    /**
     * system_server 里「双击熄屏」焦点回调用：当前焦点应用是否在用户的禁用名单里。
     * 只读 LSPosed 远程偏好（进程内缓存），名单为空直接返回 false。
     */
    public static boolean shouldSkipDoubleTapSleepForPackage(@NonNull XposedModule module, @Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        String raw = readStringForHook(
                module,
                Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES,
                DEFAULT_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES);
        return RearScreenWakeMatcher.matchesAnyPackage(raw, packageName);
    }

    /** 背屏中心音乐控件 Hook 用：用户追加的音乐应用名单原始串（未解析）。 */
    @NonNull
    public static String readMusicWidgetWhitelistPackagesForHook(@NonNull XposedModule module) {
        return readStringForHook(
                module,
                Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES,
                DEFAULT_MUSIC_WIDGET_WHITELIST_PACKAGES);
    }

    /** 背屏中心小部件页 Hook 用：用户选中的小部件列表原始串（未解析）。 */
    @NonNull
    public static String readRearAppWidgetsForHook(@NonNull XposedModule module) {
        return readStringForHook(module, Constants.KEY_REAR_APP_WIDGETS, DEFAULT_REAR_APP_WIDGETS);
    }

    /**
     * 被 Hook 进程内（如背屏面板上）专用：只通过 XposedModule 实例读写 LSPosed 远程偏好，
     * 不触碰被 Hook 应用的本地 SharedPreferences（那和模块主 App 不是同一个文件）。
     */
    private static SharedPreferences remotePrefsFromModule() {
        if (sModule == null) return null;
        try {
            return sModule.getRemotePreferences(Constants.PREF_GROUP);
        } catch (Throwable e) {
            Log.w(TAG, "remote prefs via module failed", e);
            return null;
        }
    }

    public static boolean readDisableLongPressForRemote() {
        return readRemoteBooleanWithOverride(
                Constants.KEY_DISABLE_LONG_PRESS_EDIT,
                DEFAULT_DISABLE_LONG_PRESS_EDIT);
    }

    public static boolean requestDisableLongPressWrite(@NonNull Context context, boolean disabled) {
        return requestPanelPreferenceWrite(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, disabled);
    }

    public static boolean readRemoveWallpaperLimitForRemote() {
        return readRemoteBooleanWithOverride(
                Constants.KEY_REMOVE_WALLPAPER_LIMIT,
                DEFAULT_REMOVE_WALLPAPER_LIMIT);
    }

    public static boolean requestRemoveWallpaperLimitWrite(@NonNull Context context, boolean enabled) {
        return requestPanelPreferenceWrite(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT, enabled);
    }

    public static boolean readEnableBatteryRingForRemote() {
        return readRemoteBooleanWithOverride(
                Constants.KEY_ENABLE_BATTERY_RING,
                DEFAULT_ENABLE_BATTERY_RING);
    }

    @NonNull
    public static String readBatteryColorForRemote(@NonNull String key) {
        SharedPreferences prefs = remotePrefsFromModule();
        if (prefs == null) return DEFAULT_BATTERY_COLOR;
        String value = prefs.getString(key, DEFAULT_BATTERY_COLOR);
        return value == null ? DEFAULT_BATTERY_COLOR : value;
    }

    @NonNull
    public static String readBatteryPercentPositionForRemote() {
        SharedPreferences prefs = remotePrefsFromModule();
        if (prefs == null) return DEFAULT_BATTERY_PERCENT_POSITION;
        String value = prefs.getString(
                Constants.KEY_BATTERY_PERCENT_POSITION,
                DEFAULT_BATTERY_PERCENT_POSITION);
        return value == null ? DEFAULT_BATTERY_PERCENT_POSITION : value;
    }

    /** 电量位置 / 线条缩放（快捷面板 / 宿主进程内的模块缓存）。 */
    @NonNull
    public static String readBatteryLayoutForRemote(@NonNull String key) {
        return readStringForRemote(key, batteryLayoutDefault(key));
    }

    @NonNull
    public static String readRearScreenOffTimeoutForRemote() {
        return normalizeRearScreenOffTimeout(
                readStringForRemote(Constants.KEY_REAR_SCREEN_OFF_TIMEOUT, DEFAULT_REAR_SCREEN_OFF_TIMEOUT));
    }

    @NonNull
    public static String readAutoWallpaperIntervalForRemote() {
        return AutoWallpaperRotationPolicy.normalizePreferenceValue(readStringForRemote(
                Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES, DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES));
    }

    public static boolean requestEnableBatteryRingWrite(@NonNull Context context, boolean enabled) {
        return requestPanelPreferenceWrite(context, Constants.KEY_ENABLE_BATTERY_RING, enabled);
    }

    public static boolean readRemoveAppCardLimitForRemote() {
        return readRemoteBooleanWithOverride(
                Constants.KEY_REMOVE_APP_CARD_LIMIT,
                DEFAULT_REMOVE_APP_CARD_LIMIT);
    }

    public static boolean requestRemoveAppCardLimitWrite(@NonNull Context context, boolean enabled) {
        return requestPanelPreferenceWrite(context, Constants.KEY_REMOVE_APP_CARD_LIMIT, enabled);
    }

    private static boolean requestPanelPreferenceWrite(@NonNull Context context,
                                                       @NonNull String key,
                                                       boolean value) {
        try {
            Bundle extras = new Bundle();
            extras.putBoolean(Constants.EXTRA_PREFERENCE_VALUE, value);
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + Constants.PANEL_PREFERENCE_AUTHORITY),
                    Constants.PANEL_PREFERENCE_METHOD_SET,
                    key,
                    extras);
            boolean accepted = result != null
                    && result.getBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
            if (accepted) {
                setRemoteBooleanOverride(key, value);
            }
            return accepted;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to request panel preference write for " + key, e);
            return false;
        }
    }

    /** 面板请求写字符串偏好；成功后在宿主进程记一份覆盖值。 */
    public static boolean requestPanelStringWrite(@NonNull Context context,
                                                  @NonNull String key,
                                                  @NonNull String value) {
        try {
            Bundle extras = new Bundle();
            extras.putString(Constants.EXTRA_PREFERENCE_VALUE, value);
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + Constants.PANEL_PREFERENCE_AUTHORITY),
                    Constants.PANEL_PREFERENCE_METHOD_SET_STRING,
                    key,
                    extras);
            boolean accepted = result != null
                    && result.getBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
            if (accepted) {
                setRemoteStringOverride(key, value);
            }
            return accepted;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to request panel string write for " + key, e);
            return false;
        }
    }

    /**
     * 面板可写的字符串键与合法取值。取值必须与界面上的枚举完全一致：面板来自宿主进程，
     * 不能让它把任意字符串写进模块偏好。返回规范化后的值，非法时返回 null。
     */
    @Nullable
    static String normalizePanelStringValue(@Nullable String key, @Nullable String value) {
        if (key == null || value == null) return null;
        switch (key) {
            case Constants.KEY_REAR_SCREEN_OFF_TIMEOUT: {
                String normalized = normalizeRearScreenOffTimeout(value);
                for (hook.HyperBackscreen.model.RearScreenOffTimeout entry
                        : hook.HyperBackscreen.model.RearScreenOffTimeout.getEntries()) {
                    if (entry.getPrefValue().equals(normalized)) return normalized;
                }
                return null;
            }
            case Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES: {
                for (hook.HyperBackscreen.model.AutoWallpaperInterval entry
                        : hook.HyperBackscreen.model.AutoWallpaperInterval.getEntries()) {
                    if (entry.getPrefValue().equals(value)) return value;
                }
                return null;
            }
            case Constants.KEY_BATTERY_RING_STYLE: {
                for (hook.HyperBackscreen.batteryring.BatteryRingStyle entry
                        : hook.HyperBackscreen.batteryring.BatteryRingStyle.getEntries()) {
                    if (entry.getPrefValue().equals(value)) return value;
                }
                return null;
            }
            default:
                return null;
        }
    }

    static boolean applyPanelStringPreference(@NonNull Context context,
                                              @NonNull String key,
                                              @NonNull String value) {
        String normalized = normalizePanelStringValue(key, value);
        if (normalized == null) return false;
        XposedService service = ModuleApp.awaitReadyService();
        if (service == null) return false;
        try {
            SharedPreferences prefs = service.getRemotePreferences(Constants.PREF_GROUP);
            if (!prefs.edit().putString(key, normalized).commit()) return false;
            local(context).edit().putString(key, normalized).remove(PENDING_UI_PREFIX + key).apply();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Failed to apply panel string preference " + key, error);
            return false;
        }
    }

    static boolean isPanelWritableKey(@Nullable String key) {
        return Constants.KEY_DISABLE_LONG_PRESS_EDIT.equals(key)
                || Constants.KEY_REMOVE_WALLPAPER_LIMIT.equals(key)
                || Constants.KEY_ENABLE_BATTERY_RING.equals(key)
                || Constants.KEY_REMOVE_APP_CARD_LIMIT.equals(key);
    }

    static boolean applyPanelPreference(@NonNull Context context,
                                        @NonNull String key,
                                        boolean value) {
        if (!isPanelWritableKey(key)) return false;
        // Reject an unavailable service without leaving a queued write behind a reverted switch.
        XposedService service = ModuleApp.awaitReadyService();
        if (service == null) return false;
        try {
            SharedPreferences prefs = service.getRemotePreferences(Constants.PREF_GROUP);
            if (!prefs.edit().putBoolean(key, value).commit()) return false;
            local(context).edit().putBoolean(key, value).remove(PENDING_PANEL_PREFIX + key)
                    .remove(PENDING_UI_PREFIX + key).apply();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Failed to apply panel preference " + key, error);
            return false;
        }
    }

    static boolean flushPanelPreference(@NonNull Context context,
                                        @NonNull String key) {
        if (!isPanelWritableKey(key)) return false;
        SharedPreferences localPrefs = local(context);
        String pendingKey = PENDING_PANEL_PREFIX + key;
        if (!localPrefs.contains(pendingKey)) return true;
        XposedService service = ModuleApp.getService();
        if (service == null) return false;
        try {
            boolean value = localPrefs.getBoolean(pendingKey, false);
            SharedPreferences remotePrefs = service.getRemotePreferences(Constants.PREF_GROUP);
            if (!remotePrefs.edit().putBoolean(key, value).commit()) return false;
            if (!localPrefs.edit().putBoolean(key, value).remove(pendingKey).commit()) return false;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to flush panel preference " + key, e);
            return false;
        }
    }

    /** 服务就绪时先补交待同步值，再对齐远程、本地和默认值；补交失败的值继续保留。 */
    public static void syncOnServiceAvailable(@NonNull Context context, @NonNull XposedService service) {
        try {
            SharedPreferences localPrefs = local(context);
            SharedPreferences remotePrefs = service.getRemotePreferences(Constants.PREF_GROUP);
            flushPanelPreference(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT);
            flushPanelPreference(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT);
            flushPanelPreference(context, Constants.KEY_ENABLE_BATTERY_RING);
            flushPanelPreference(context, Constants.KEY_REMOVE_APP_CARD_LIMIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_LONG_PRESS_EDIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_APP_CARD);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_APP_CARD_INSTALL_REQUEST);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_18_PRO_FEATURES);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_BATTERY_RING);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REMOVE_WALLPAPER_LIMIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REMOVE_APP_CARD_LIMIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_FIX_REAR_SCREEN_APPLY);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_FACE_ENROLLMENT_COMPAT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_REAR_SCREEN_COVER);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_THEME_SETTINGS_SHORTCUT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REAR_VIDEO_RESUME);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REAR_VIDEO_UNLOCK_LIMITS);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REAR_VIDEO_KEEP_AUDIO);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_REAR_LYRIC);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REAR_LYRIC_IN_AOD);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_REAR_APP_WIDGETS);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_COLOR_IDLE);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_COLOR_CHARGING);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_COLOR_LOW);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_COLOR_IDLE);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_COLOR_LOW);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_POSITION);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_OFFSET_X);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_OFFSET_Y);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_RING_SCALE);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_RING_STROKE);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_TEXT_SIZE);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_RING_TRACK);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_RING_STYLE);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_LOW_THRESHOLD);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_CHARGE_SWEEP);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_PERCENT_SHADOW);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_AUTO_WALLPAPER_ORDER);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_OFF_TIMEOUT);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_SETTINGS_ENTRY);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_SCHEDULE_START);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_SCHEDULE_END);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_SCHEDULE_MODE);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_REMOVE_APP_CARD_LIMIT, DEFAULT_REMOVE_APP_CARD_LIMIT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
            syncFaceEnrollmentCompatKey(localPrefs, remotePrefs);
            syncBooleanKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME,
                    DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME);
            syncBooleanKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED,
                    DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_APP_CARD, DEFAULT_ENABLE_APP_CARD);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_APP_CARD_INSTALL_REQUEST, false);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_18_PRO_FEATURES, DEFAULT_ENABLE_18_PRO_FEATURES);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_BATTERY_RING, DEFAULT_ENABLE_BATTERY_RING);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_REAR_SCREEN_COVER, DEFAULT_DISABLE_REAR_SCREEN_COVER);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, DEFAULT_DISABLE_DOUBLE_TAP_WAKE);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_REAR_VIDEO_RESUME, DEFAULT_REAR_VIDEO_RESUME);
            syncBooleanKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_VIDEO_UNLOCK_LIMITS,
                    DEFAULT_REAR_VIDEO_UNLOCK_LIMITS);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_REAR_VIDEO_KEEP_AUDIO, DEFAULT_REAR_VIDEO_KEEP_AUDIO);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_REAR_LYRIC, DEFAULT_ENABLE_REAR_LYRIC);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_REAR_LYRIC_IN_AOD, DEFAULT_REAR_LYRIC_IN_AOD);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                    DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES,
                    DEFAULT_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_MUSIC_WIDGET_WHITELIST_PACKAGES,
                    DEFAULT_MUSIC_WIDGET_WHITELIST_PACKAGES);
            syncStringKey(localPrefs, remotePrefs, Constants.KEY_REAR_APP_WIDGETS, DEFAULT_REAR_APP_WIDGETS);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_COLOR_IDLE,
                    DEFAULT_BATTERY_COLOR);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_COLOR_CHARGING,
                    DEFAULT_BATTERY_COLOR);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_COLOR_LOW,
                    DEFAULT_BATTERY_COLOR);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_COLOR_IDLE,
                    DEFAULT_BATTERY_COLOR);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING,
                    DEFAULT_BATTERY_COLOR);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_COLOR_LOW,
                    DEFAULT_BATTERY_COLOR);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_POSITION,
                    DEFAULT_BATTERY_PERCENT_POSITION);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_OFFSET_X,
                    DEFAULT_BATTERY_PERCENT_OFFSET_X);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT,
                    DEFAULT_BATTERY_PERCENT_OFFSET_X);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT,
                    DEFAULT_BATTERY_PERCENT_OFFSET_X);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_OFFSET_Y,
                    DEFAULT_BATTERY_PERCENT_OFFSET_Y);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_RING_SCALE,
                    DEFAULT_BATTERY_RING_SCALE);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_RING_STROKE,
                    DEFAULT_BATTERY_RING_STROKE);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_TEXT_SIZE,
                    DEFAULT_BATTERY_PERCENT_TEXT_SIZE);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_RING_TRACK,
                    DEFAULT_BATTERY_RING_TRACK);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_RING_STYLE,
                    DEFAULT_BATTERY_RING_STYLE);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_LOW_THRESHOLD,
                    DEFAULT_BATTERY_LOW_THRESHOLD);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_CHARGE_SWEEP,
                    DEFAULT_BATTERY_CHARGE_SWEEP);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_BATTERY_PERCENT_SHADOW,
                    DEFAULT_BATTERY_PERCENT_SHADOW);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES,
                    DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_AUTO_WALLPAPER_ORDER,
                    DEFAULT_AUTO_WALLPAPER_ORDER);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_OFF_TIMEOUT,
                    DEFAULT_REAR_SCREEN_OFF_TIMEOUT);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_SETTINGS_ENTRY,
                    DEFAULT_SETTINGS_ENTRY);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_SCHEDULE_START,
                    DEFAULT_REAR_SCREEN_SCHEDULE_START);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_SCHEDULE_END,
                    DEFAULT_REAR_SCREEN_SCHEDULE_END);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_SCHEDULE_MODE,
                    DEFAULT_REAR_SCREEN_SCHEDULE_MODE);
            // 兜底：上面的清单是逐键写的，新加的键容易漏（曾漏过数字字重等）。
            // 这里把所有残留的待同步值按类型补交，再把所有可备份的远程键对齐一遍。
            for (Map.Entry<String, ?> entry : new java.util.HashMap<>(localPrefs.getAll()).entrySet()) {
                String pendingKey = entry.getKey();
                if (!pendingKey.startsWith(PENDING_UI_PREFIX)) continue;
                String key = pendingKey.substring(PENDING_UI_PREFIX.length());
                if (entry.getValue() instanceof Boolean) {
                    flushUiBooleanPreference(localPrefs, remotePrefs, key);
                } else if (entry.getValue() instanceof String) {
                    flushUiStringPreference(localPrefs, remotePrefs, key);
                }
            }
            for (Map.Entry<String, String> entry : BACKUP_STRING_DEFAULTS.entrySet()) {
                if (LOCAL_ONLY_KEYS.contains(entry.getKey())) continue;
                syncStringKey(localPrefs, remotePrefs, entry.getKey(), entry.getValue());
            }
            long pending = localPrefs.getAll().keySet().stream()
                    .filter(key -> key.startsWith(PENDING_UI_PREFIX) || key.startsWith(PENDING_PANEL_PREFIX))
                    .count();
            DiagnosticLogStore.recordLocalAsync(context, "preference_reconciliation finished pending_count=" + pending);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to sync prefs on service available", e);
            DiagnosticLogStore.recordLocalAsync(context, "preference_reconciliation failed error=" + e.getClass().getSimpleName());
        }
    }

    private static void flushUiBooleanPreference(@NonNull SharedPreferences localPrefs,
                                                 @NonNull SharedPreferences remotePrefs,
                                                 @NonNull String key) {
        String pendingKey = PENDING_UI_PREFIX + key;
        if (!localPrefs.contains(pendingKey)) return;
        boolean value = localPrefs.getBoolean(pendingKey, localPrefs.getBoolean(key, false));
        if (remotePrefs.edit().putBoolean(key, value).commit()) {
            localPrefs.edit().putBoolean(key, value).remove(pendingKey).commit();
        }
    }

    private static void flushUiStringPreference(@NonNull SharedPreferences localPrefs,
                                                @NonNull SharedPreferences remotePrefs,
                                                @NonNull String key) {
        String pendingKey = PENDING_UI_PREFIX + key;
        if (!localPrefs.contains(pendingKey)) return;
        String value = localPrefs.getString(pendingKey, localPrefs.getString(key, ""));
        if (value == null) value = "";
        if (remotePrefs.edit().putString(key, value).commit()) {
            localPrefs.edit().putString(key, value).remove(pendingKey).commit();
        }
    }

    private static void syncBooleanKey(@NonNull SharedPreferences localPrefs,
                                       @NonNull SharedPreferences remotePrefs,
                                       @NonNull String key,
                                       boolean def) {
        if (localPrefs.contains(PENDING_UI_PREFIX + key)) return;
        if (remotePrefs.contains(key)) {
            localPrefs.edit().putBoolean(key, remotePrefs.getBoolean(key, def)).apply();
        } else if (localPrefs.contains(key)) {
            remotePrefs.edit().putBoolean(key, localPrefs.getBoolean(key, def)).apply();
        } else {
            remotePrefs.edit().putBoolean(key, def).apply();
            localPrefs.edit().putBoolean(key, def).apply();
        }
    }

    /**
     * 人脸兼容的首次同步：新键从未写过时，把旧开关（fix_rear_screen_apply）的值抄过来。
     *
     * <p>不能直接用 {@link #syncBooleanKey}：它在两边都没有键时会写入默认值 false，
     * 那样「未写过」这个信号就丢了，老用户升级后已开的人脸兼容会被静默关掉。
     */
    private static void syncFaceEnrollmentCompatKey(@NonNull SharedPreferences localPrefs,
                                                    @NonNull SharedPreferences remotePrefs) {
        String key = Constants.KEY_FACE_ENROLLMENT_COMPAT;
        if (localPrefs.contains(PENDING_UI_PREFIX + key)) return;
        if (remotePrefs.contains(key)) {
            localPrefs.edit()
                    .putBoolean(key, remotePrefs.getBoolean(key, DEFAULT_FACE_ENROLLMENT_COMPAT))
                    .apply();
            return;
        }
        boolean migrated = DEFAULT_FACE_ENROLLMENT_COMPAT;
        if (remotePrefs.contains(Constants.KEY_FIX_REAR_SCREEN_APPLY)) {
            migrated = remotePrefs.getBoolean(
                    Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
        } else if (localPrefs.contains(Constants.KEY_FIX_REAR_SCREEN_APPLY)) {
            migrated = localPrefs.getBoolean(
                    Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
        }
        remotePrefs.edit().putBoolean(key, migrated).apply();
        localPrefs.edit().putBoolean(key, migrated).apply();
    }

    private static void syncStringKey(@NonNull SharedPreferences localPrefs,
                                      @NonNull SharedPreferences remotePrefs,
                                      @NonNull String key,
                                      @NonNull String def) {
        if (localPrefs.contains(PENDING_UI_PREFIX + key)) return;
        if (remotePrefs.contains(key)) {
            localPrefs.edit().putString(key, remotePrefs.getString(key, def)).apply();
        } else if (localPrefs.contains(key)) {
            remotePrefs.edit().putString(key, localPrefs.getString(key, def)).apply();
        } else {
            remotePrefs.edit().putString(key, def).apply();
            localPrefs.edit().putString(key, def).apply();
        }
    }

}
