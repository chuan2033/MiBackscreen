package hook.HyperBackscreen.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import hook.HyperBackscreen.app.ModuleApp;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.PackageListCodec;
import hook.HyperBackscreen.common.RearScreenWakeMatcher;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.service.XposedService;

public final class PrefsBridge {
    private static final String TAG = Constants.LOG_TAG + ":PrefsBridge";
    private static final String PENDING_PANEL_PREFIX = "__pending_panel__";
    private static final String PENDING_UI_PREFIX = "__pending_ui__";

    public static final boolean DEFAULT_DISABLE_LONG_PRESS_EDIT = true;
    public static final boolean DEFAULT_REMOVE_WALLPAPER_LIMIT = true;
    public static final boolean DEFAULT_FIX_REAR_SCREEN_APPLY = false;
    private static final boolean DEFAULT_FLOATING_NAV_BAR = false;
    private static final boolean DEFAULT_LIQUID_GLASS = false;
    public static final boolean DEFAULT_ENABLE_SWIPE_PANEL = true;
    public static final boolean DEFAULT_DISABLE_REAR_SCREEN_COVER = false;
    public static final boolean DEFAULT_DISABLE_DOUBLE_TAP_WAKE = false;
    public static final String DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES = "";
    public static final boolean DEFAULT_THEME_SETTINGS_SHORTCUT = true;

    private PrefsBridge() {
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

    /** UI 侧读取：远程优先并回写本地缓存；服务未就绪时退回本地值。 */
    private static boolean readForUi(@NonNull Context context, @NonNull String key, boolean def) {
        SharedPreferences remote = remote();
        if (remote != null) {
            boolean value = remote.getBoolean(key, def);
            local(context).edit().putBoolean(key, value).apply();
            return value;
        }
        return local(context).getBoolean(key, def);
    }

    /** UI 侧写入：本地与远程双写，Hook 端下次读取即生效，无需重启。 */
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
    }

    private static String readStringForUi(@NonNull Context context, @NonNull String key, @NonNull String def) {
        SharedPreferences remote = remote();
        if (remote != null) {
            String value = remote.getString(key, def);
            local(context).edit().putString(key, value).apply();
            return value == null ? def : value;
        }
        String value = local(context).getString(key, def);
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

    public static void writeDisableLongPressFromUi(@NonNull Context context, boolean disabled) {
        writeFromUi(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, disabled);
    }

    public static boolean readRemoveWallpaperLimitForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
    }

    public static void writeRemoveWallpaperLimitFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT, enabled);
    }

    public static boolean readFixRearScreenApplyForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
    }

    public static void writeFixRearScreenApplyFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_FIX_REAR_SCREEN_APPLY, enabled);
    }

    public static boolean readEnableSwipePanelForUi(@NonNull Context context) {
        return readForUi(context, Constants.KEY_ENABLE_SWIPE_PANEL, DEFAULT_ENABLE_SWIPE_PANEL);
    }

    public static void writeEnableSwipePanelFromUi(@NonNull Context context, boolean enabled) {
        writeFromUi(context, Constants.KEY_ENABLE_SWIPE_PANEL, enabled);
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

    public static boolean shouldBlockLongPressEdit(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT);
    }

    public static boolean shouldRemoveWallpaperLimit(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
    }

    public static boolean shouldFixRearScreenApply(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
    }

    public static boolean shouldEnableSwipePanel(@NonNull XposedModule module) {
        return readForHook(module, Constants.KEY_ENABLE_SWIPE_PANEL, DEFAULT_ENABLE_SWIPE_PANEL);
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

    public static boolean shouldSkipDoubleTapWakeForPackage(@NonNull XposedModule module, @Nullable String packageName) {
        if (packageName == null || !shouldDisableDoubleTapWake(module)) return false;
        String raw = readStringForHook(
                module,
                Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
        return PackageListCodec.parse(raw).contains(packageName);
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
        SharedPreferences prefs = remotePrefsFromModule();
        return prefs != null
                ? prefs.getBoolean(Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT)
                : DEFAULT_DISABLE_LONG_PRESS_EDIT;
    }

    public static boolean requestDisableLongPressWrite(@NonNull Context context, boolean disabled) {
        return requestPanelPreferenceWrite(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, disabled);
    }

    public static boolean readRemoveWallpaperLimitForRemote() {
        SharedPreferences prefs = remotePrefsFromModule();
        return prefs != null
                ? prefs.getBoolean(Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT)
                : DEFAULT_REMOVE_WALLPAPER_LIMIT;
    }

    public static boolean requestRemoveWallpaperLimitWrite(@NonNull Context context, boolean enabled) {
        return requestPanelPreferenceWrite(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT, enabled);
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
            return result != null
                    && result.getBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to request panel preference write for " + key, e);
            return false;
        }
    }

    static boolean isPanelWritableKey(@Nullable String key) {
        return Constants.KEY_DISABLE_LONG_PRESS_EDIT.equals(key)
                || Constants.KEY_REMOVE_WALLPAPER_LIMIT.equals(key);
    }

    static boolean stagePanelPreference(@NonNull Context context,
                                        @NonNull String key,
                                        boolean value) {
        if (!isPanelWritableKey(key)) return false;
        return local(context).edit()
                .putBoolean(key, value)
                .putBoolean(PENDING_PANEL_PREFIX + key, value)
                .commit();
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
            return localPrefs.edit().putBoolean(key, value).remove(pendingKey).commit();
        } catch (Throwable e) {
            Log.w(TAG, "Failed to flush panel preference " + key, e);
            return false;
        }
    }

    /** 服务就绪时对齐本地与远程：远程有值以远程为准，否则用本地值补齐远程，都没有则写入默认值。 */
    public static void syncOnServiceAvailable(@NonNull Context context, @NonNull XposedService service) {
        try {
            SharedPreferences localPrefs = local(context);
            SharedPreferences remotePrefs = service.getRemotePreferences(Constants.PREF_GROUP);
            flushPanelPreference(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT);
            flushPanelPreference(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_LONG_PRESS_EDIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_REMOVE_WALLPAPER_LIMIT);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_FIX_REAR_SCREEN_APPLY);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_ENABLE_SWIPE_PANEL);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_REAR_SCREEN_COVER);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE);
            flushUiBooleanPreference(localPrefs, remotePrefs, Constants.KEY_THEME_SETTINGS_SHORTCUT);
            flushUiStringPreference(localPrefs, remotePrefs, Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_ENABLE_SWIPE_PANEL, DEFAULT_ENABLE_SWIPE_PANEL);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_REAR_SCREEN_COVER, DEFAULT_DISABLE_REAR_SCREEN_COVER);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, DEFAULT_DISABLE_DOUBLE_TAP_WAKE);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_THEME_SETTINGS_SHORTCUT, DEFAULT_THEME_SETTINGS_SHORTCUT);
            syncStringKey(
                    localPrefs,
                    remotePrefs,
                    Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                    DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to sync prefs on service available", e);
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
