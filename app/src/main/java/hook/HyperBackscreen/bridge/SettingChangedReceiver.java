package hook.HyperBackscreen.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import hook.HyperBackscreen.common.Constants;

public class SettingChangedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (!Constants.ACTION_SETTING_CHANGED.equals(intent.getAction())) {
            return;
        }
        String key = intent.getStringExtra(Constants.EXTRA_SETTING_KEY);
        if (key == null) {
            return;
        }
        if (intent.hasExtra(Constants.EXTRA_SETTING_VALUE)) {
            Object value = intent.getExtras().get(Constants.EXTRA_SETTING_VALUE);
            if (value instanceof Boolean) {
                PrefsBridge.applyIncomingSettingForUi(context, key, (Boolean) value);
            } else if (value instanceof Integer) {
                PrefsBridge.applyIncomingSettingForUiInt(context, key, (Integer) value);
            }
        }
    }
}
