package hook.HyperBackscreen.hook;

import android.app.Application;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;
import android.widget.RemoteViews;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.BackgroundTasks;
import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.ui.SwipePanelHost;
import io.github.libxposed.api.XposedModule;

/** Debug builds only. Shell/root DUMP permission required; fixtures use reserved notification IDs. */
@android.annotation.SuppressLint({"DiscouragedApi", "NotificationPermission", "PrivateApi"})
public final class DebugHostProbe {
    private static boolean registered;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static final int TEST_ID = 960927;
    private static final String TEST_TAG = "MiBackscreen.DebugProbe";

    public static synchronized void register(Context context, XposedModule module, ClassLoader loader) {
        if (registered) return;
        Application app = (Application) context.getApplicationContext();
        if (!app.getPackageName().equals(Application.getProcessName())) return;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) { activity = new WeakReference<>(a); }
            public void onActivityResumed(Activity a) { activity = new WeakReference<>(a); }
            public void onActivityDestroyed(Activity a) { if (activity.get() == a) activity.clear(); }
            public void onActivityStarted(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
        });
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String command = intent.getStringExtra("command");
                try {
                    if ("face-status".equals(command)
                            && Constants.THEME_STORE_PACKAGE.equals(ctx.getPackageName())) {
                        BackgroundTasks.execute(() -> {
                            try {
                                Object count = Class.forName(
                                        "com.rearScreen.helper.RearScreenBiometricHelper", false, loader)
                                        .getMethod("k", Context.class).invoke(null, ctx);
                                Log.i(Constants.LOG_TAG, "PROBE theme face count=" + count
                                        + " manageBiometric=" + ctx.checkSelfPermission(
                                        "android.permission.MANAGE_BIOMETRIC"));
                            } catch (Exception error) {
                                Log.e(Constants.LOG_TAG, "PROBE face-status failed", error);
                            }
                        });
                    } else if ("cards".equals(command)) cards(loader);
                    else if ("prefs".equals(command)) BackgroundTasks.execute(() -> preferences(ctx));
                    else if ("battery-ring".equals(command)) {
                        boolean shown = hook.HyperBackscreen.ui.battery.BatteryRingHost.previewForDebug(
                                intent.getIntExtra("percent", -1), intent.getBooleanExtra("charging", false));
                        Log.i(Constants.LOG_TAG, "PROBE battery ring available=" + shown);
                    }
                    else if ("panel".equals(command)) {
                        Activity host = activity.get();
                        if (host == null) throw new IllegalStateException("No host activity");
                        SwipePanelHost.show(host, null);
                        Log.i(Constants.LOG_TAG, "PROBE panel shown");
                    } else throw new IllegalArgumentException("Unknown probe command");
                } catch (Throwable error) { Log.e(Constants.LOG_TAG, "PROBE FAIL " + command, error); }
            }
        }, new IntentFilter(Constants.MODULE_PACKAGE + ".DEBUG_PROBE"),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        registered = true;
        Log.i(Constants.LOG_TAG, "PROBE registered: " + app.getPackageName());
    }

    private static void preferences(Context context) {
        boolean original = PrefsBridge.readDisableLongPressForRemote();
        boolean passed = true;
        try {
            for (boolean value : new boolean[]{!original, original}) {
                if (!PrefsBridge.requestDisableLongPressWrite(context, value)) {
                    passed = false; break;
                }
                long deadline = android.os.SystemClock.uptimeMillis() + 2000;
                while (PrefsBridge.readDisableLongPressForRemote() != value
                        && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20);
                if (PrefsBridge.readDisableLongPressForRemote() != value) passed = false;
            }
        } finally {
            boolean restored = PrefsBridge.requestDisableLongPressWrite(context, original);
            Log.i(Constants.LOG_TAG, "PROBE prefs roundTrip=" + passed + " restoreAccepted=" + restored);
        }
    }

    private static void cards(ClassLoader loader) throws Exception {
        Class<?> manager = loader.loadClass(Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS);
        Object instance = null;
        for (Field f : manager.getDeclaredFields()) {
            if (f.getType() == manager && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true); instance = f.get(null); break;
            }
        }
        if (instance == null) throw new IllegalStateException("Missing launcher singleton");
        List<?> source = (List<?>) manager.getDeclaredMethod("c").invoke(instance);
        long before = source.stream().filter(x -> PanelAppCard.isModuleEntry(loader, x)).count();
        if (before > 1) throw new IllegalStateException("Duplicate module entry in c()");
        manager.getDeclaredMethod("d", Consumer.class).invoke(instance, (Consumer<Object>) value -> {
            List<?> list = (List<?>) value;
            long count = list.stream().filter(x -> PanelAppCard.isModuleEntry(loader, x)).count();
            if (count > 1) { Log.e(Constants.LOG_TAG, "PROBE FAIL dispatch module count=" + count); return; }
            Log.i(Constants.LOG_TAG, "PROBE cards source=" + source.size() + " dispatch="
                    + list.size() + " module=" + count);
        });
    }

    private static int size(Notification notification) {
        Parcel p = Parcel.obtain();
        try { notification.writeToParcel(p, 0); return p.dataSize(); }
        finally { p.recycle(); }
    }
}
