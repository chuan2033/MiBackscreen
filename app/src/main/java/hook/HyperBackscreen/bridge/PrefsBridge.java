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
import hook.HyperBackscreen.common.RearScreenWakeMatcher;
import hook.HyperBackscreen.hook.AutoWallpaperRotationPolicy;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.service.XposedService;

public final class PrefsBridge {
    private static final String TAG = Constants.LOG_TAG + ":PrefsBridge";
    private static final String PENDING_PANEL_PREFIX = "__pending_panel__";
    static final String PENDING_UI_PREFIX = "__pending_ui__";
    private static final ConcurrentHashMap<String, Boolean> sRemoteBooleanOverrides =
            new ConcurrentHashMap<>();

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
    public static final String DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES = "0";
    /** 背屏息屏超时覆盖；"0" = 跟随系统，其余为毫秒值。 */
    public static final String DEFAULT_REAR_SCREEN_OFF_TIMEOUT = "0";
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
    public static final boolean DEFAULT_ENABLE_PICKUP = true;
    public static final boolean DEFAULT_DISABLE_REAR_SCREEN_COVER = false;
    public static final boolean DEFAULT_DISABLE_DOUBLE_TAP_WAKE = false;
    public static final String DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES = "";
    public static final boolean DEFAULT_THEME_SETTINGS_SHORTCUT = true;
    public static final String DEFAULT_PICKUP_ISLAND_SELECTION = "";

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
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_DISABLE_REAR_SCREEN_COVER, DEFAULT_DISABLE_REAR_SCREEN_COVER);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, DEFAULT_DISABLE_DOUBLE_TAP_WAKE);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_ENABLE_PICKUP, DEFAULT_ENABLE_PICKUP);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_CHECK_UPDATES, DEFAULT_CHECK_UPDATES);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_FLOATING_NAV_BAR, DEFAULT_FLOATING_NAV_BAR);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_LIQUID_GLASS, DEFAULT_LIQUID_GLASS);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_BOTTOM_BAR_BLUR, DEFAULT_BOTTOM_BAR_BLUR);
        BACKUP_BOOLEAN_DEFAULTS.put(Constants.KEY_SHOW_FUNCTION_COUNT, DEFAULT_SHOW_FUNCTION_COUNT);

        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_COLOR_IDLE, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_COLOR_CHARGING, DEFAULT_BATTERY_COLOR);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_BATTERY_COLOR_LOW, DEFAULT_BATTERY_COLOR);
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
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES, DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_REAR_SCREEN_OFF_TIMEOUT, DEFAULT_REAR_SCREEN_OFF_TIMEOUT);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES, DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
        BACKUP_STRING_DEFAULTS.put(Constants.KEY_PICKUP_ISLAND_SELECTION, DEFAULT_PICKUP_ISLAND_SELECTION);
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
        sRemoteBooleanOverrides.put(key, value);
    }

    private static boolean readRemoteBooleanWithOverride(@NonNull String key, boolean def) {
        Boolean override = sRemoteBooleanOverrides.get(key);
        if (override != null) return override;
        SharedPreferences prefs = remotePrefsFromModule();
        return prefs != null ? prefs.getBoolean(key, def) : def;
    }

    /** UI 双写；服务未连接时保存待同步值，重连后补交到 RemotePreferences。 */
    private static void writeFromUi(@NonNull Context context, @NonNull String key, boolean value) {
        SharedPreferences localPrefs = local(context);
        SharedPreferences remote = remote();
        if (remote != null) {
            remote.edit().putBoolean(key, value).apply();
            localPrefs.edit()
                    .putBoolean(key, value)
                    .remove(PENDING_UI_PREFIX + key)
                    .apply();
        } else {
            localPrefs.edit()
                    .putBoolean(key, value)
                    .putBoolean(PENDING_UI_PREFIX + key, value)
                    .apply();
        }
        DiagnosticLogStore.recordLocalAsync(context, "preference_write key=" + key
                + " value=" + value + " destination=" + (remote != null ? "remote_apply_requested" : "local_pending"));
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
        SharedPreferences remote = remote();
        if (remote != null) {
            remote.edit().putString(key, value).apply();
            localPrefs.edit()
                    .putString(key, value)
                    .remove(PENDING_UI_PREFIX + key)
                    .apply();
        } else {
            localPrefs.edit()
                    .putString(key, value)
                    .putString(PENDING_UI_PREFIX + key, value)
                    .apply();
        }
        DiagnosticLogStore.recordLocalAsync(context, "preference_write key=" + key
                + " destination=" + (remote != null ? "remote_apply_requested" : "local_pending"));
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

    /** 电量颜色覆盖（UI 侧）：空字符串表示跟随系统。 */
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

    /** 电量颜色覆盖（Hook 侧）：空字符串表示跟随系统。 */
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

    /**
     * 导出会改变行为/观感的偏好（布尔值转成 "true"/"false"，其余保持字符串）。
     * 顺序与 [BACKUP_BOOLEAN_DEFAULTS] / [BACKUP_STRING_DEFAULTS] 一致，方便人工比对。
     */
    @NonNull
    public static Map<String, String> exportPreferencesForBackup(@NonNull Context context) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : BACKUP_BOOLEAN_DEFAULTS.entrySet()) {
            values.put(entry.getKey(),
                    String.valueOf(readForUi(context, entry.getKey(), entry.getValue())));
        }
        for (Map.Entry<String, String> entry : BACKUP_STRING_DEFAULTS.entrySet()) {
            values.put(entry.getKey(),
                    readStringForUi(context, entry.getKey(), entry.getValue()));
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
            writeFromUi(context, entry.getKey(),
                    "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed));
            applied++;
        }
        for (String key : BACKUP_STRING_DEFAULTS.keySet()) {
            String raw = values.get(key);
            if (raw == null) continue;
            writeStringFromUi(context, key, raw);
            applied++;
        }
        return applied;
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
        if (remotePrefs == null) {
            localPrefs.edit()
                    .putBoolean(Constants.KEY_ENABLE_APP_CARD, true)
                    .putBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, true)
                    .putBoolean(PENDING_UI_PREFIX + Constants.KEY_ENABLE_APP_CARD, true)
                    .putBoolean(PENDING_UI_PREFIX + Constants.KEY_APP_CARD_INSTALL_REQUEST, true)
                    .apply();
            DiagnosticLogStore.recordLocalAsync(context,
                    "app_card_install_request destination=local_pending");
            return false;
        }
        boolean saved = remotePrefs.edit()
                .putBoolean(Constants.KEY_ENABLE_APP_CARD, true)
                .putBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, true)
                .commit();
        SharedPreferences.Editor editor = localPrefs.edit()
                .putBoolean(Constants.KEY_ENABLE_APP_CARD, true)
                .putBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, true);
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

    public static boolean readEnablePickupForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_ENABLE_PICKUP, DEFAULT_ENABLE_PICKUP);
    }

    public static void writeEnablePickupFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_ENABLE_PICKUP, enabled);
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

    public static boolean readThemeSettingsShortcutForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
    }

    public static void writeThemeSettingsShortcutFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_THEME_SETTINGS_SHORTCUT, enabled);
    }

    /** 取件码页在模块进程写，Hook 在小爱进程读，必须走远程偏好。 */
    @NonNull
    public static String readPickupIslandSelectionForUi(@NonNull Context context) {
        return readStringForUi(
                context,
                Constants.KEY_PICKUP_ISLAND_SELECTION,
                DEFAULT_PICKUP_ISLAND_SELECTION);
    }

    @SuppressLint("ApplySharedPref")
    public static void writePickupIslandSelectionFromUi(@NonNull Context context, @NonNull String value) {
        tryWritePickupIslandSelectionFromUi(context, value);
    }

    /** UI feedback needs the actual commit result, not an optimistic checkmark. */
    @SuppressLint("ApplySharedPref")
    public static boolean tryWritePickupIslandSelectionFromUi(@NonNull Context context, @NonNull String value) {
        return savePickupIslandSelectionFromUi(context, value) != PickupSaveResult.FAILED;
    }

    public enum PickupSaveResult { SYNCED, PENDING, FAILED }

    /** Return the destination used by this write; checking service availability afterwards is racy. */
    @SuppressLint("ApplySharedPref")
    public static PickupSaveResult savePickupIslandSelectionFromUi(@NonNull Context context, @NonNull String value) {
        SharedPreferences localPrefs = local(context);
        SharedPreferences remote = remote();
        if (remote != null) {
            boolean committed = remote.edit()
                    .putString(Constants.KEY_PICKUP_ISLAND_SELECTION, value)
                    .commit();
            if (committed) {
                localPrefs.edit()
                        .putString(Constants.KEY_PICKUP_ISLAND_SELECTION, value)
                        .remove(PENDING_UI_PREFIX + Constants.KEY_PICKUP_ISLAND_SELECTION)
                        .commit();
            }
            return committed ? PickupSaveResult.SYNCED : PickupSaveResult.FAILED;
        } else {
            return localPrefs.edit()
                    .putString(Constants.KEY_PICKUP_ISLAND_SELECTION, value)
                    .putString(PENDING_UI_PREFIX + Constants.KEY_PICKUP_ISLAND_SELECTION, value)
                    .commit() ? PickupSaveResult.PENDING : PickupSaveResult.FAILED;
        }
    }

    @NonNull
    public static String readPickupIslandSelectionForHook(@NonNull XposedModule module) {
        return readStringForHook(
                module,
                Constants.KEY_PICKUP_ISLAND_SELECTION,
                DEFAULT_PICKUP_ISLAND_SELECTION);
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
        Boolean override = sRemoteBooleanOverrides.get(Constants.KEY_DISABLE_LONG_PRESS_EDIT);
        return override != null
                ? override
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

    public static void clearAppCardInstallRequest(@NonNull XposedModule module) {
        try {
            SharedPreferences prefs = module.getRemotePreferences(Constants.PREF_GROUP);
            if (prefs != null) {
                prefs.edit().putBoolean(Constants.KEY_APP_CARD_INSTALL_REQUEST, false).commit();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to clear app card install request", e);
        }
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

    public static boolean shouldEnablePickup(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_ENABLE_PICKUP, DEFAULT_ENABLE_PICKUP);
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
        String def = batteryLayoutDefault(key);
        SharedPreferences prefs = remotePrefsFromModule();
        if (prefs == null) return def;
        String value = prefs.getString(key, def);
        return value == null ? def : value;
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
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_PICKUP);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_APP_CARD);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_APP_CARD_INSTALL_REQUEST);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_18_PRO_FEATURES);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_BATTERY_RING);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REMOVE_WALLPAPER_LIMIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REMOVE_APP_CARD_LIMIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_FIX_REAR_SCREEN_APPLY);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_FACE_ENROLLMENT_COMPAT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_REAR_SCREEN_COVER);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_THEME_SETTINGS_SHORTCUT);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_PICKUP_ISLAND_SELECTION);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_COLOR_IDLE);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_COLOR_CHARGING);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_BATTERY_COLOR_LOW);
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
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES);
            flushUiStringPreference(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_OFF_TIMEOUT);
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
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_PICKUP, DEFAULT_ENABLE_PICKUP);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_APP_CARD, DEFAULT_ENABLE_APP_CARD);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_APP_CARD_INSTALL_REQUEST, false);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_18_PRO_FEATURES, DEFAULT_ENABLE_18_PRO_FEATURES);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_BATTERY_RING, DEFAULT_ENABLE_BATTERY_RING);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_REAR_SCREEN_COVER, DEFAULT_DISABLE_REAR_SCREEN_COVER);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, DEFAULT_DISABLE_DOUBLE_TAP_WAKE);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                    DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_PICKUP_ISLAND_SELECTION,
                    DEFAULT_PICKUP_ISLAND_SELECTION);
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
                    Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES,
                    DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_REAR_SCREEN_OFF_TIMEOUT,
                    DEFAULT_REAR_SCREEN_OFF_TIMEOUT);
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
