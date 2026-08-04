package hook.HyperBackscreen.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import hook.HyperBackscreen.app.ModuleApp;
import hook.HyperBackscreen.common.Constants;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.service.XposedService;

public final class PrefsBridge {
    private static final String TAG = Constants.LOG_TAG + ":PrefsBridge";

    public static final boolean DEFAULT_DISABLE_LONG_PRESS_EDIT = true;
    public static final boolean DEFAULT_REMOVE_WALLPAPER_LIMIT = true;
    public static final boolean DEFAULT_FIX_REAR_SCREEN_APPLY = false;
    private static final boolean DEFAULT_FLOATING_NAV_BAR = false;
    private static final boolean DEFAULT_LIQUID_GLASS = false;

    private PrefsBridge() {
    }

    @NonNull
    private static SharedPreferences local(@NonNull Context context) {
        return context.getSharedPreferences(Constants.PREF_GROUP, Context.MODE_PRIVATE);
    }

    @Nullable
    private static SharedPreferences remote() {
        try {
            XposedService service = ModuleApp.getService();
            if (service == null) {
                return null;
            }
            return service.getRemotePreferences(Constants.PREF_GROUP);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to get remote prefs", e);
            return null;
        }
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
        local(context).edit().putBoolean(key, value).apply();
        SharedPreferences remote = remote();
        if (remote != null) {
            remote.edit().putBoolean(key, value).apply();
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

    /** 服务就绪时对齐本地与远程：远程有值以远程为准，否则用本地值补齐远程，都没有则写入默认值。 */
    public static void syncOnServiceAvailable(@NonNull Context context, @NonNull XposedService service) {
        try {
            SharedPreferences localPrefs = local(context);
            SharedPreferences remotePrefs = service.getRemotePreferences(Constants.PREF_GROUP);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_DISABLE_LONG_PRESS_EDIT, DEFAULT_DISABLE_LONG_PRESS_EDIT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_REMOVE_WALLPAPER_LIMIT, DEFAULT_REMOVE_WALLPAPER_LIMIT);
            syncBooleanKey(localPrefs, remotePrefs, Constants.KEY_FIX_REAR_SCREEN_APPLY, DEFAULT_FIX_REAR_SCREEN_APPLY);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to sync prefs on service available", e);
        }
    }

    private static void syncBooleanKey(@NonNull SharedPreferences localPrefs,
                                       @NonNull SharedPreferences remotePrefs,
                                       @NonNull String key,
                                       boolean def) {
        if (remotePrefs.contains(key)) {
            localPrefs.edit().putBoolean(key, remotePrefs.getBoolean(key, def)).apply();
        } else if (localPrefs.contains(key)) {
            remotePrefs.edit().putBoolean(key, localPrefs.getBoolean(key, def)).apply();
        } else {
            remotePrefs.edit().putBoolean(key, def).apply();
            localPrefs.edit().putBoolean(key, def).apply();
        }
    }
}
