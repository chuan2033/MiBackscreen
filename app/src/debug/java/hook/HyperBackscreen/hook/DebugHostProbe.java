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
                    else if ("panel".equals(command)) {
                        Activity host = activity.get();
                        if (host == null) throw new IllegalStateException("No host activity");
                        SwipePanelHost.show(host, null);
                        Log.i(Constants.LOG_TAG, "PROBE panel shown");
                    } else if ("pickup-post".equals(command)) pickup(ctx);
                    else if ("pickup-refresh".equals(command)) {
                        Method refresh = PickupCodeHook.class.getDeclaredMethod("requestRefresh", Context.class);
                        refresh.setAccessible(true);
                        refresh.invoke(null, ctx);
                    } else if ("pickup-status".equals(command)) pickupStatus(ctx);
                    else if ("pickup-clean".equals(command)) {
                        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                        nm.cancel(TEST_TAG, TEST_ID);
                        nm.cancel(TEST_ID);
                        Log.i(Constants.LOG_TAG, "PROBE pickup fixtures removed");
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
        if (before != 0) throw new IllegalStateException("Module entry leaked into c()");
        manager.getDeclaredMethod("d", Consumer.class).invoke(instance, (Consumer<Object>) value -> {
            List<?> list = (List<?>) value;
            long count = list.stream().filter(x -> PanelAppCard.isModuleEntry(loader, x)).count();
            if (count != 1) { Log.e(Constants.LOG_TAG, "PROBE FAIL dispatch module count=" + count); return; }
            List<Object> clean = PanelAppCard.withoutModuleEntry(loader, list);
            boolean ok = clean != null && clean.size() == list.size() - 1;
            Log.i(Constants.LOG_TAG, "PROBE cards source=" + source.size() + " dispatch="
                    + list.size() + " module=" + count + " saveStrip=" + ok);
        });
    }

    private static void pickup(Context ctx) throws Exception {
        Method register = PickupCodeHook.class.getDeclaredMethod("ensureRefreshReceiver", Context.class);
        register.setAccessible(true); register.invoke(null, ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        String channel = "memory_island_channel";
        if (nm.getNotificationChannel(channel) == null)
            throw new IllegalStateException("Host memory channel absent; create a memory card first");
        int labelId = ctx.getResources().getIdentifier("memory_scene_name_delivery", "string", ctx.getPackageName());
        String label = ctx.getString(labelId);
        org.json.JSONObject params = new org.json.JSONObject()
                .put("business", "memory")
                .put("param_island", new org.json.JSONObject().put("bigIslandArea", new org.json.JSONObject()
                        .put("textInfo", new org.json.JSONObject().put("title", "1-2345,2-6789"))
                        .put("imageTextInfoLeft", new org.json.JSONObject().put("textInfo",
                                new org.json.JSONObject().put("title", label)))));
        Bundle extras = new Bundle();
        extras.putString("miui.focus.param.custom", params.toString());
        String[][] views = {{"miui.focus.rv", "memory_scene_focused_remote_view"},
                {"miui.focus.rv.island.expand", "memory_scene_focused_remote_view"},
                {"miui.focus.rv.tiny", "memory_scene_tiny_remote_view"},
                {"miui.rear.rv", "memory_scene_rear_remote_view"}};
        for (String[] view : views) {
            int id = ctx.getResources().getIdentifier(view[1], "layout", ctx.getPackageName());
            if (id == 0) throw new IllegalStateException("Missing layout " + view[1]);
            extras.putParcelable(view[0], new RemoteViews(ctx.getPackageName(), id));
        }
        Notification notification = new Notification.Builder(ctx, channel)
                .setSmallIcon(ctx.getApplicationInfo().icon).setContentTitle("1-2345,2-6789")
                .setSubText("MiBackscreen debug station").setContentText("Synthetic local test")
                .setOnlyAlertOnce(true).addExtras(extras).build();
        int originalSize = size(notification);
        nm.notify(TEST_ID, notification);
        nm.notify(TEST_TAG, TEST_ID, notification);
        if (originalSize != size(notification)) throw new IllegalStateException("Caller notification mutated");
        Log.i(Constants.LOG_TAG, "PROBE pickup posted both overloads, callerBytes=" + originalSize);
    }

    private static int size(Notification notification) {
        Parcel p = Parcel.obtain();
        try { notification.writeToParcel(p, 0); return p.dataSize(); }
        finally { p.recycle(); }
    }

    private static void pickupStatus(Context ctx) {
        int count = 0;
        for (android.service.notification.StatusBarNotification current :
                ctx.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (current.getId() != TEST_ID) continue;
            count++;
            Log.i(Constants.LOG_TAG, "PROBE pickup active tag=" + current.getTag()
                    + " bytes=" + size(current.getNotification()));
        }
        Log.i(Constants.LOG_TAG, "PROBE pickup active count=" + count);
    }
}
