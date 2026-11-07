package hook.HyperBackscreen.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.Binder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import hook.HyperBackscreen.common.Constants;

/** Synchronous, permission-protected preference bridge for the hooked system app. */
public final class PreferenceBridgeProvider extends ContentProvider {
    private static final String TAG = Constants.LOG_TAG + ":PreferenceProvider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        // 权限 MANAGE_ACTIVITY_TASKS 除系统应用外桌面（最近任务角色）也持有，再按调用方包名收窄：
        // 只接受背屏中心（快捷面板所在进程）和模块自己。
        if (!isTrustedCaller(context)) {
            result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
            return result;
        }
        if (Constants.PANEL_DIAGNOSTIC_METHOD_APPEND.equals(method)) {
            String message = extras != null
                    ? extras.getString(Constants.EXTRA_DIAGNOSTIC_MESSAGE)
                    : null;
            boolean accepted = context != null
                    && message != null
                    && !message.isBlank()
                    && DiagnosticLogStore.appendLocal(context.getApplicationContext(), message);
            result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, accepted);
            return result;
        }

        if (Constants.PANEL_PREFERENCE_METHOD_SET_STRING.equals(method)) {
            String value = extras != null ? extras.getString(Constants.EXTRA_PREFERENCE_VALUE) : null;
            String normalized = PrefsBridge.normalizePanelStringValue(arg, value);
            boolean applied = context != null
                    && normalized != null
                    && PrefsBridge.applyPanelStringPreference(context.getApplicationContext(), arg, normalized);
            if (applied && Constants.KEY_REAR_SCREEN_OFF_TIMEOUT.equals(arg)) {
                // 与主 App 改档位时一样，把系统设置 subscreen_display_time 也同步过去（需要 root）。
                // 用规范化后的值，和写进偏好的保持一致；同步排不进队列也不影响这次写入已成功。
                try {
                    hook.HyperBackscreen.ui.util.RearScreenTimeoutSync.INSTANCE.apply(
                            hook.HyperBackscreen.ui.RearScreenOffTimeout.Companion.fromPref(normalized));
                } catch (RuntimeException error) {
                    Log.w(TAG, "Rear screen timeout sync skipped", error);
                }
            }
            result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, applied);
            Log.d(TAG, "Panel string preference " + arg + "=" + value
                    + (applied ? " applied" : " rejected"));
            return result;
        }

        if (!Constants.PANEL_PREFERENCE_METHOD_SET.equals(method)
                || !PrefsBridge.isPanelWritableKey(arg)
                || extras == null
                || !extras.containsKey(Constants.EXTRA_PREFERENCE_VALUE)) {
            result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
            return result;
        }

        if (context == null) {
            result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
            return result;
        }

        boolean value = extras.getBoolean(Constants.EXTRA_PREFERENCE_VALUE, false);
        boolean applied = PrefsBridge.applyPanelPreference(context.getApplicationContext(), arg, value);
        result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, applied);
        Log.d(TAG, "Panel preference " + arg + "=" + value
                + (applied ? " applied" : " rejected"));
        return result;
    }

    private static boolean isTrustedCaller(@Nullable Context context) {
        if (context == null) return false;
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) {
            if (Constants.TARGET_PACKAGE.equals(name)) return true;
        }
        return false;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("call() only");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("call() only");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("call() only");
    }
}
