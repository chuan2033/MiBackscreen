package hook.HyperBackscreen.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
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
        if (!Constants.PANEL_PREFERENCE_METHOD_SET.equals(method)
                || !PrefsBridge.isPanelWritableKey(arg)
                || extras == null
                || !extras.containsKey(Constants.EXTRA_PREFERENCE_VALUE)) {
            result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
            return result;
        }

        Context context = getContext();
        if (context == null) {
            result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, false);
            return result;
        }

        boolean value = extras.getBoolean(Constants.EXTRA_PREFERENCE_VALUE, false);
        boolean staged = PrefsBridge.stagePanelPreference(context.getApplicationContext(), arg, value);
        boolean flushed = staged && PrefsBridge.flushPanelPreference(context.getApplicationContext(), arg);
        result.putBoolean(Constants.EXTRA_PREFERENCE_ACCEPTED, staged);
        Log.d(TAG, "Panel preference " + arg + "=" + value
                + (flushed ? " saved" : " queued"));
        return result;
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
