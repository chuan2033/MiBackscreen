package hook.HyperBackscreen.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.annotation.NonNull;

import hook.HyperBackscreen.common.Constants;

public final class SettingsSyncBridge {
    private static volatile boolean receiverInstalled = false;

    private SettingsSyncBridge() {
    }

    public static synchronized void ensureHookReceiverInstalled(@NonNull Context context) {
        if (receiverInstalled) {
            return;
        }
        receiverInstalled = true;
        IntentFilter filter = new IntentFilter(Constants.ACTION_SETTING_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(new HookSettingReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(new HookSettingReceiver(), filter);
        }
    }

    public static void sendBooleanSetting(@NonNull Context context, @NonNull String key, boolean value) {
        Intent intent = new Intent(Constants.ACTION_SETTING_CHANGED);
        intent.putExtra(Constants.EXTRA_SETTING_KEY, key);
        intent.putExtra(Constants.EXTRA_SETTING_VALUE, value);
        context.sendBroadcast(intent);
    }

    public static void sendIntSetting(@NonNull Context context, @NonNull String key, int value) {
        Intent intent = new Intent(Constants.ACTION_SETTING_CHANGED);
        intent.putExtra(Constants.EXTRA_SETTING_KEY, key);
        intent.putExtra(Constants.EXTRA_SETTING_VALUE, value);
        context.sendBroadcast(intent);
    }

    private static class HookSettingReceiver extends BroadcastReceiver {
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
                    PrefsBridge.applyIncomingSettingForHook(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    PrefsBridge.applyIncomingSettingForHookInt(key, (Integer) value);
                }
            }
        }
    }
}
