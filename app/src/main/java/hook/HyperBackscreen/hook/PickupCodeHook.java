package hook.HyperBackscreen.hook;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.PickupCodes;
import io.github.libxposed.api.XposedModule;

final class PickupCodeHook {
    private static final String MANAGER = "com.xiaomi.voiceassistant.memory.island.d";
    private static final String SCENE = "com.xiaomi.voiceassistant.memory.island.c";
    private static final String BRAND = "com.miui.voiceassist.data.entities.MemoryBrand";
    private static final String ACTIVITY = Constants.MODULE_PACKAGE + ".ui.pickup.PickupCodesActivity";
    private static final String ACTION_MEMORY_SCENE_BUTTON =
            "com.xiaomi.voiceassistant.ACTION_MEMORY_SCENE_BUTTON";
    private static final String ACTION_RECEIVER =
            "com.xiaomi.voiceassistant.memory.island.MemorySceneActionReceiver";
    private static final String EXTRA_NOTIFICATION_ID = "notification_id";
    // 同一轮记忆岛通知通常在几百毫秒内连续提交；缩短窗口避免用户下一次记忆继承旧会话。
    private static final long PICKUP_BATCH_GAP_MS = 2000L;
    private static final int MAX_CAPTURED_NOTIFICATIONS = 16;
    private static final Object SESSION_LOCK = new Object();
    private static final Map<String, String> PICKUP_SESSIONS = new HashMap<>();
    private static final Map<String, String> PICKUP_OPEN_TOKENS = new LinkedHashMap<>();
    private static final Map<String, String> CONFIRMED_PAGES = new LinkedHashMap<>();
    private static long lastPickupBuildAt;
    private static String currentPickupSession = "";
    private static volatile String activePageToken = "";
    private static BroadcastReceiver refreshReceiver;
    private static final Object LAST_NOTIFICATION_LOCK = new Object();
    private static final Map<String, CapturedNotification> pickupNotifications = new LinkedHashMap<>();
    private static volatile Context pickupContext;
    private static XposedModule refreshModule;
    private static final ThreadLocal<Boolean> postingRefresh = ThreadLocal.withInitial(() -> false);
    private static final String ISLAND_PARAM = "miui.focus.param.custom";

    private static final class CapturedNotification {
        final int id;
        final String tag;
        final String identity;

        CapturedNotification(int id, String tag, String identity) {
            this.id = id;
            this.tag = tag;
            this.identity = identity;
        }
    }

    private PickupCodeHook() {}

    static boolean install(XposedModule module, ClassLoader loader) {
        try {
            // Verified against XiaoAi 8.2.10.2222. Match complete signatures and layout IDs.
            Class<?> manager = Class.forName(MANAGER, false, loader);
            Class<?> scene = Class.forName(SCENE, false, loader);
            Class<?> brand = Class.forName(BRAND, false, loader);
            Method sceneValue = scene.getDeclaredMethod("getValue");
            sceneValue.setAccessible(true);
            Method focused = manager.getDeclaredMethod("b", Context.class, String.class,
                    String.class, String.class, String.class, Bitmap.class, brand, scene,
                    PendingIntent.class, PendingIntent.class, boolean.class);
            Method tiny = manager.getDeclaredMethod("h", Context.class, String.class,
                    String.class, String.class, Bitmap.class, brand, scene,
                    PendingIntent.class, boolean.class);
            boolean first = hook(module, focused, sceneValue, 7,
                    "memory_scene_focused_remote_view", "memory_scene_focus_content_area",
                    "memory_scene_focus_title");
            boolean second = hook(module, tiny, sceneValue, 6,
                    "memory_scene_tiny_remote_view", "memory_scene_tiny_content_area",
                    "memory_scene_tiny_title");
            boolean notification = hookNotification(module, loader);
            boolean action = hookMemoryActionReceiver(module, loader);
            return first || second || notification || action;
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG, "Pickup card hook targets unavailable", error);
            return false;
        }
    }

    private static boolean hookMemoryActionReceiver(XposedModule module, ClassLoader loader) {
        try {
            Class<?> receiver = Class.forName(ACTION_RECEIVER, false, loader);
            Method onReceive = receiver.getDeclaredMethod("onReceive", Context.class, Intent.class);
            module.hook(onReceive)
                    .setExceptionMode(XposedModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        Intent intent = args.size() > 1 && args.get(1) instanceof Intent
                                ? (Intent) args.get(1) : null;
                        Context context = args.size() > 0 && args.get(0) instanceof Context
                                ? (Context) args.get(0) : null;
                        CapturedNotification confirmed = null;
                        String pageToken = activePageToken;
                        // XiaoAi may cancel the notification inside onReceive, so match it first.
                        if (context != null && intent != null
                                && ACTION_MEMORY_SCENE_BUTTON.equals(intent.getAction())) {
                            try {
                                confirmed = findActiveCapturedPickupNotification(context,
                                        intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1));
                            } catch (RuntimeException error) {
                                Log.w(Constants.LOG_TAG, "Unable to match pickup confirmation", error);
                            }
                        }
                        Object result = chain.proceed();
                        if (confirmed != null && !pageToken.isEmpty()) {
                            try {
                                dismissCapturedPickupNotification(context, confirmed);
                            } catch (RuntimeException error) {
                                Log.w(Constants.LOG_TAG, "Unable to dismiss pickup notification", error);
                            }
                            sendPickupConfirmed(context, pageToken, confirmed.identity);
                        }
                        return result;
                    });
            module.log(Log.INFO, Constants.LOG_TAG, "Pickup confirmation hook installed");
            return true;
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Unable to install pickup confirmation hook", error);
            return false;
        }
    }

    private static void sendPickupConfirmed(Context context, String token, String identity) {
        synchronized (SESSION_LOCK) {
            CONFIRMED_PAGES.put(token, identity);
            while (CONFIRMED_PAGES.size() > MAX_CAPTURED_NOTIFICATIONS) {
                CONFIRMED_PAGES.remove(CONFIRMED_PAGES.keySet().iterator().next());
            }
        }
        Log.i(Constants.LOG_TAG, "Pickup confirmation received; closing code page");
        context.sendBroadcast(new Intent(PickupCodes.ACTION_CONFIRMED)
                .putExtra(PickupCodes.EXTRA_TOKEN, token)
                .putExtra(PickupCodes.EXTRA_IDENTITY, identity)
                .setPackage(Constants.MODULE_PACKAGE));
    }

    /**
     * XiaoAi 已经把岛卡组装进 Notification 后，重新提交同一个通知即可让 SystemUI 重绘。
     * 这样切换取件码时不需要重启 XiaoAi，也不会影响其它类型的灵动岛。
     */
    private static boolean hookNotification(XposedModule module, ClassLoader loader) {
        try {
            Class<?> notifications = Class.forName("android.app.NotificationManager", false, loader);
            boolean plain = hookNotificationMethod(module,
                    notifications.getDeclaredMethod("notify", int.class, Notification.class));
            boolean tagged = hookNotificationMethod(module,
                    notifications.getDeclaredMethod("notify", String.class, int.class,
                            Notification.class));
            if (!plain && !tagged) return false;
            module.log(Log.INFO, Constants.LOG_TAG, "Pickup notification refresh hook installed");
            return true;
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Unable to install pickup notification refresh hook", error);
            return false;
        }
    }

    private static boolean hookNotificationMethod(XposedModule module, Method notify) {
        try {
            module.hook(notify)
                    .setExceptionMode(XposedModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        int notificationIndex = args.size() - 1;
                        int idIndex = args.size() == 2 ? 0 : 1;
                        Notification notification = (Notification) args.get(notificationIndex);
                        if (!postingRefresh.get() && pickupContext != null) {
                            try {
                                patchNotification(notification, pickupContext, module);
                            } catch (Throwable error) {
                                module.log(Log.WARN, Constants.LOG_TAG,
                                        "Unable to update pickup notification", error);
                            }
                        }
                        Object result = chain.proceed();
                        if (!postingRefresh.get()) {
                            try {
                                capturePickupNotification((Integer) args.get(idIndex),
                                        args.size() == 3 ? (String) args.get(0) : null,
                                        notification, module);
                            } catch (Throwable error) {
                                Log.w(Constants.LOG_TAG, "Unable to capture pickup notification", error);
                            }
                        }
                        return result;
                    });
            return true;
        } catch (Throwable error) {
            Log.w(Constants.LOG_TAG, "Unable to hook notification method", error);
            return false;
        }
    }

    private static void capturePickupNotification(int id, String tag, Notification notification,
                                                   XposedModule module) {
        Bundle extras = notification.extras;
        if (!isPickupNotification(notification)) return;
        try {
            synchronized (LAST_NOTIFICATION_LOCK) {
                while (pickupNotifications.size() >= MAX_CAPTURED_NOTIFICATIONS) {
                    String oldest = pickupNotifications.keySet().iterator().next();
                    pickupNotifications.remove(oldest);
                }
                pickupNotifications.put(notificationKey(id, tag),
                        new CapturedNotification(id, tag, identityForNotification(notification)));
                refreshModule = module;
            }
            Log.i(Constants.LOG_TAG, "Pickup notification captured for island refresh");
        } catch (Throwable error) {
            Log.w(Constants.LOG_TAG, "Unable to copy pickup notification", error);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static boolean hook(XposedModule module, Method target, Method sceneValue,
                                int sceneIndex, String layoutName, String areaName, String titleName) {
        if (target.getReturnType() != RemoteViews.class) return false;
        try {
            module.hook(target)
                    .setExceptionMode(XposedModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        try {
                            List<Object> args = chain.getArgs();
                            String scene = (String) sceneValue.invoke(args.get(sceneIndex));
                            String title = (String) args.get(1);
                            if (!(original instanceof RemoteViews)
                                    || !PickupCodes.shouldAddEntry(scene, title)) return original;
                            Context context = (Context) args.get(0);
                            if (!Constants.VOICE_ASSIST_PACKAGE.equals(context.getPackageName())) return original;
                            ensureRefreshReceiver(context);
                            RemoteViews views = (RemoteViews) original;
                            int layoutId = context.getResources().getIdentifier(layoutName, "layout",
                                    Constants.VOICE_ASSIST_PACKAGE);
                            int areaId = context.getResources().getIdentifier(areaName, "id",
                                    Constants.VOICE_ASSIST_PACKAGE);
                            int titleId = context.getResources().getIdentifier(titleName, "id",
                                    Constants.VOICE_ASSIST_PACKAGE);
                            if (layoutId == 0 || areaId == 0 || views.getLayoutId() != layoutId
                                    || !Constants.VOICE_ASSIST_PACKAGE.equals(views.getPackage())) return original;

                            List<String> parsed = PickupCodes.parse(title);
                            String station = PickupCodes.station((String) args.get(3));
                            String identity = PickupCodes.identity(parsed, station);
                            String session = sessionForIdentity(identity);
                            String token = openToken(module, identity, session);
                            String codes = String.join(",", parsed);
                            Intent intent = new Intent(PickupCodes.ACTION_VIEW)
                                    .setComponent(new ComponentName(Constants.MODULE_PACKAGE, ACTIVITY))
                                    .setData(new Uri.Builder().scheme("mibackscreen").authority("pickup")
                                            .appendPath(identity).build())
                                    .putExtra(PickupCodes.EXTRA_CODES, codes)
                                    .putExtra(PickupCodes.EXTRA_STATION, station)
                                    .putExtra(PickupCodes.EXTRA_SESSION, session)
                                    .putExtra(PickupCodes.EXTRA_TOKEN, token);
                            PendingIntent open = PendingIntent.getActivity(context, identity.hashCode(), intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            RemoteViews enhanced = new RemoteViews(views);
                            // 用户在取件码页勾选"显示在灵动岛"后，卡片只保留被选中的码。
                            List<String> visible = PickupCodes.applyIslandSelection(
                                     parsed, PickupCodes.displayIdentity(parsed, station),
                                     selectionForCard(module, parsed, station));
                            if (titleId != 0) {
                                // 每行两个、最多 4 个（2 行）；标题 TextView 默认 maxLines=1 会被截断。
                                setPickupTitle(context, enhanced, titleId, titleName, visible);
                                module.log(Log.DEBUG, Constants.LOG_TAG,
                                        "Pickup card list laid out: " + visible.size() + "/" + parsed.size());
                            }
                            enhanced.setOnClickPendingIntent(areaId, open);
                            module.log(Log.DEBUG, Constants.LOG_TAG,
                                    "Pickup card entry attached: " + layoutName);
                            return enhanced;
                        } catch (Throwable error) {
                            module.log(Log.WARN, Constants.LOG_TAG, "Unable to attach pickup card entry", error);
                            return original;
                        }
                    });
            module.log(Log.INFO, Constants.LOG_TAG, "Pickup card hook installed: " + layoutName);
            return true;
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG, "Unable to install pickup card hook", error);
            return false;
        }
    }

    private static synchronized void ensureRefreshReceiver(Context context) {
        pickupContext = context.getApplicationContext();
        if (refreshReceiver != null) return;
        try {
            refreshReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) return;
                    if (PickupCodes.ACTION_PAGE_OPENED.equals(intent.getAction())) {
                        String token = intent.getStringExtra(PickupCodes.EXTRA_TOKEN);
                        String confirmedIdentity;
                        synchronized (SESSION_LOCK) {
                            if (token == null || !PICKUP_OPEN_TOKENS.containsValue(token)) return;
                            confirmedIdentity = CONFIRMED_PAGES.get(token);
                        }
                        activePageToken = token;
                        dismissRecognitionOverlay(ctx);
                        if (confirmedIdentity != null) {
                            sendPickupConfirmed(ctx, token, confirmedIdentity);
                        }
                        return;
                    }
                    if (!Constants.ACTION_REFRESH_PICKUP_ISLAND.equals(intent.getAction())) return;
                    Log.i(Constants.LOG_TAG, "Pickup island refresh requested");
                    refreshPickupNotifications(ctx);
                }
            };
            IntentFilter filter = new IntentFilter(Constants.ACTION_REFRESH_PICKUP_ISLAND);
            filter.addAction(PickupCodes.ACTION_PAGE_OPENED);
            pickupContext.registerReceiver(refreshReceiver, filter, Context.RECEIVER_EXPORTED);
            Log.i(Constants.LOG_TAG, "Pickup island refresh receiver registered");
        } catch (Throwable error) {
            refreshReceiver = null;
            Log.w(Constants.LOG_TAG, "Unable to register pickup island refresh receiver", error);
        }
    }

    private static void dismissRecognitionOverlay(Context context) {
        try {
            Class<?> uiManager = Class.forName("com.xiaomi.voiceassistant.UiManager", false,
                    context.getClassLoader());
            Object existing = uiManager.getDeclaredMethod("getExistingInstance").invoke(null);
            if (existing == null) return;
            // Use XiaoAi's own activity handoff to release its window and launcher gesture state.
            uiManager.getDeclaredMethod("hideCardForActivityNotStopEngine").invoke(existing);
            Log.i(Constants.LOG_TAG, "Pickup page opened; recognition overlay dismissed");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(Constants.LOG_TAG, "Unable to dismiss pickup recognition overlay", error);
        }
    }

    @SuppressLint("NotificationPermission")
    private static void refreshPickupNotifications(Context context) {
        XposedModule module;
        Map<String, CapturedNotification> captured;
        synchronized (LAST_NOTIFICATION_LOCK) {
            if (pickupNotifications.isEmpty() || refreshModule == null) {
                Log.w(Constants.LOG_TAG, "Pickup island refresh skipped: no captured notifications");
                return;
            }
            captured = new HashMap<>(pickupNotifications);
            module = refreshModule;
        }

        try {
            NotificationManager notifications =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notifications == null) return;
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(Constants.LOG_TAG, "Pickup island refresh skipped: host notification permission denied");
                return;
            }
            Map<String, StatusBarNotification> active = new HashMap<>();
            for (StatusBarNotification current : notifications.getActiveNotifications()) {
                if (isPickupNotification(current.getNotification())) {
                    active.put(notificationKey(current.getId(), current.getTag()), current);
                }
            }
            for (Map.Entry<String, CapturedNotification> entry : captured.entrySet()) {
                CapturedNotification capturedNotification = entry.getValue();
                StatusBarNotification current = active.get(entry.getKey());
                if (current == null) {
                    synchronized (LAST_NOTIFICATION_LOCK) {
                        pickupNotifications.remove(entry.getKey());
                    }
                    continue;
                }
                Notification snapshot = copyNotification(current.getNotification());
                if (!patchNotification(snapshot, context, module)) continue;
                snapshot.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
                postingRefresh.set(true);
                try {
                    notifications.notify(capturedNotification.tag, capturedNotification.id, snapshot);
                    Log.i(Constants.LOG_TAG, "Pickup island notification reposted: " + entry.getKey());
                } finally {
                    postingRefresh.remove();
                }
            }
        } catch (Throwable error) {
            Log.w(Constants.LOG_TAG, "Unable to refresh pickup island notification", error);
        }
    }

    private static CapturedNotification findActiveCapturedPickupNotification(
            Context context, int notificationId) {
        if (notificationId < 0) return null;
        NotificationManager notifications =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications == null) return null;
        Map<String, StatusBarNotification> active = new HashMap<>();
        for (StatusBarNotification current : notifications.getActiveNotifications()) {
            if (isPickupNotification(current.getNotification())) {
                active.put(notificationKey(current.getId(), current.getTag()), current);
            }
        }
        synchronized (LAST_NOTIFICATION_LOCK) {
            pickupNotifications.keySet().removeIf(key -> !active.containsKey(key));
            for (CapturedNotification captured : pickupNotifications.values()) {
                if (captured.id == notificationId
                        && active.containsKey(notificationKey(captured.id, captured.tag))) {
                    return captured;
                }
            }
            return null;
        }
    }

    private static void dismissCapturedPickupNotification(Context context,
                                                           CapturedNotification notification) {
        if (notification == null) return;
        NotificationManager notifications =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications != null) {
            notifications.cancel(notification.tag, notification.id);
        }
        synchronized (LAST_NOTIFICATION_LOCK) {
            pickupNotifications.remove(notificationKey(notification.id, notification.tag));
        }
    }

    private static String identityForNotification(Notification notification) {
        try {
            Bundle extras = notification.extras;
            List<String> codes = PickupCodes.parse(
                    extras.getCharSequence("android.title").toString());
            CharSequence stationValue = extras.getCharSequence("android.subText");
            String station = PickupCodes.station(stationValue == null ? ""
                    : stationValue.toString());
            return PickupCodes.displayIdentity(codes, station);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String notificationKey(int id, String tag) {
        return (tag == null ? "" : tag) + "\u0000" + id;
    }

    private static boolean isPickupNotification(Notification notification) {
        if (notification == null || notification.extras == null
                || !"memory_island_channel".equals(notification.getChannelId())) return false;
        Bundle extras = notification.extras;
        CharSequence title = extras.getCharSequence("android.title");
        if (title == null || !PickupCodes.shouldAddEntry("delivery", title.toString())) return false;
        try {
            JSONObject params = new JSONObject(extras.getString(ISLAND_PARAM, ""));
            return "memory".equals(params.optString("business"))
                    && "pickup_code".equals(params.getJSONObject("param_island")
                            .getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
                            .getJSONObject("picInfo").optString("pic"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean patchNotification(Notification notification, Context context,
                                              XposedModule module) throws Exception {
            if (!isPickupNotification(notification)) return false;
            Bundle extras = new Bundle(notification.extras);
            List<String> parsed = PickupCodes.parse(extras.getCharSequence("android.title").toString());
            CharSequence stationValue = extras.getCharSequence("android.subText");
            String station = PickupCodes.station(stationValue == null ? "" : stationValue.toString());
            String identity = PickupCodes.identity(parsed, station);
            List<String> visible = PickupCodes.applyIslandSelection(
                    parsed, PickupCodes.displayIdentity(parsed, station),
                    selectionForCard(module, parsed, station));
            // The collapsed island is JSON-driven, independently of the expanded RemoteViews.
            JSONObject params = new JSONObject(extras.getString(ISLAND_PARAM));
            params.getJSONObject("param_island").getJSONObject("bigIslandArea")
                    .getJSONObject("textInfo").put("title", PickupCodes.formatCollapsedText(visible));
            params.put("updatable", true);
            extras.putString(ISLAND_PARAM, params.toString());
            patchRemoteView(extras, "miui.focus.rv", context, module,
                    "memory_scene_focused_remote_view", "memory_scene_focus_content_area",
                    "memory_scene_focus_title", visible, identity, parsed, station);
            patchRemoteView(extras, "miui.focus.rvNight", context, module,
                    "memory_scene_focused_remote_view", "memory_scene_focus_content_area",
                    "memory_scene_focus_title", visible, identity, parsed, station);
            patchRemoteView(extras, "miui.focus.rv.island.expand", context, module,
                    "memory_scene_focused_remote_view", "memory_scene_focus_content_area",
                    "memory_scene_focus_title", visible, identity, parsed, station);
            patchRemoteView(extras, "miui.focus.rv.tiny", context, module,
                    "memory_scene_tiny_remote_view", "memory_scene_tiny_content_area",
                    "memory_scene_tiny_title", visible, identity, parsed, station);
            patchRemoteView(extras, "miui.focus.rv.tinyNight", context, module,
                    "memory_scene_tiny_remote_view", "memory_scene_tiny_content_area",
                    "memory_scene_tiny_title", visible, identity, parsed, station);

            notification.extras = extras;
            return true;
    }

    @SuppressLint("DiscouragedApi")
    private static void patchRemoteView(Bundle extras, String key, Context context,
                                        XposedModule module,
                                        String layoutName, String areaName, String titleName,
                                        List<String> visible, String identity,
                                        List<String> parsed, String station) {
        Object value = extras.get(key);
        if (!(value instanceof RemoteViews)) return;
        RemoteViews views = (RemoteViews) value;
        int layoutId = context.getResources().getIdentifier(layoutName, "layout",
                Constants.VOICE_ASSIST_PACKAGE);
        int areaId = context.getResources().getIdentifier(areaName, "id",
                Constants.VOICE_ASSIST_PACKAGE);
        int titleId = context.getResources().getIdentifier(titleName, "id",
                Constants.VOICE_ASSIST_PACKAGE);
        if (layoutId == 0 || areaId == 0 || views.getLayoutId() != layoutId
                || !Constants.VOICE_ASSIST_PACKAGE.equals(views.getPackage())) return;

        RemoteViews enhanced = new RemoteViews(views);
        if (titleId != 0) {
            float textSize = setPickupTitle(context, enhanced, titleId, titleName, visible);
            if (textSize > 0 && "miui.focus.rv.island.expand".equals(key)) {
                fitExpandedTitle(context, enhanced, titleId, visible, textSize);
            }
        }
        enhanced.setOnClickPendingIntent(areaId,
                createOpenPendingIntent(context, module, identity, parsed, station));
        extras.putParcelable(key, enhanced);
    }

    private static PendingIntent createOpenPendingIntent(Context context, XposedModule module,
                                                         String identity,
                                                         List<String> parsed, String station) {
        String codes = String.join(",", parsed);
        String session = sessionForIdentity(identity);
        String token = openToken(module, identity, session);
        Intent intent = new Intent(PickupCodes.ACTION_VIEW)
                .setComponent(new ComponentName(Constants.MODULE_PACKAGE, ACTIVITY))
                .setData(new Uri.Builder().scheme("mibackscreen").authority("pickup")
                        .appendPath(identity).build())
                .putExtra(PickupCodes.EXTRA_CODES, codes)
                .putExtra(PickupCodes.EXTRA_STATION, station)
                .putExtra(PickupCodes.EXTRA_SESSION, session)
                .putExtra(PickupCodes.EXTRA_TOKEN, token);
        return PendingIntent.getActivity(context, identity.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String openToken(XposedModule module, String identity, String session) {
        String key = session + "\n" + identity;
        synchronized (SESSION_LOCK) {
            String token = PICKUP_OPEN_TOKENS.get(key);
            if (token == null) {
                token = UUID.randomUUID().toString().replace("-", "");
                PICKUP_OPEN_TOKENS.put(key, token);
                while (PICKUP_OPEN_TOKENS.size() > MAX_CAPTURED_NOTIFICATIONS * 4) {
                    PICKUP_OPEN_TOKENS.remove(PICKUP_OPEN_TOKENS.keySet().iterator().next());
                }
                PrefsBridge.writePickupOpenTokenForHook(module, identity, token);
            }
            return token;
        }
    }

    /**
     * 同一批识别生成的多个岛卡共用会话；下一批识别超过短时间间隔后开启新会话。
     * 这样连续点击 A/B 岛仍能合并分类，但下一次记忆不会把旧码带回来。
     */
    private static String sessionForIdentity(String identity) {
        synchronized (SESSION_LOCK) {
            long now = android.os.SystemClock.uptimeMillis();
            if (lastPickupBuildAt == 0 || now - lastPickupBuildAt > PICKUP_BATCH_GAP_MS) {
                currentPickupSession = Long.toHexString(now);
                PICKUP_SESSIONS.clear();
            }
            lastPickupBuildAt = now;
            String session = PICKUP_SESSIONS.get(identity);
            if (session == null) {
                session = currentPickupSession;
                PICKUP_SESSIONS.put(identity, session);
            }
            return session;
        }
    }

    private static String selectionForCard(XposedModule module, List<String> parsed, String station) {
        String stored = PrefsBridge.readPickupIslandSelectionForHook(module);
        String displayIdentity = PickupCodes.displayIdentity(parsed, station);
        String displaySelection = PickupCodes.selectionForIdentity(stored, displayIdentity);
        if (!displaySelection.isEmpty()) return displaySelection;
        if (station.isEmpty()) return "";
        // 兼容旧版按完整码串保存的记录，升级后会在页面操作时迁移为驿站记录。
        String legacy = PickupCodes.selectionForIdentity(stored, PickupCodes.identity(parsed, station));
        if (legacy.isEmpty()) return "";
        return displayIdentity + legacy.substring(legacy.indexOf('|'));
    }

    private static Notification copyNotification(Notification source) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Notification.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @SuppressLint("DiscouragedApi")
    private static float setPickupTitle(Context context, RemoteViews views, int titleId,
                                        String titleName, List<String> visible) {
        String text = PickupCodes.formatIslandText(visible);
        String[] rows = text.split("\n", -1);
        views.setTextViewText(titleId, text);
        views.setBoolean(titleId, "setSingleLine", rows.length == 1);
        views.setInt(titleId, "setMaxLines", rows.length);
        String prefix = titleName.equals("memory_scene_tiny_title")
                ? "memory_scene_tiny" : "memory_scene_focused";
        int sizeId = context.getResources().getIdentifier(prefix + "_title_text_size", "dimen",
                Constants.VOICE_ASSIST_PACKAGE);
        int widthId = context.getResources().getIdentifier(prefix + "_text_width", "dimen",
                Constants.VOICE_ASSIST_PACKAGE);
        if (sizeId != 0 && widthId != 0) {
            float defaultSize = context.getResources().getDimension(sizeId);
            // Two rows use the previous compact size to leave room for the station and action.
            float baseSize = rows.length > 1 ? defaultSize * 0.75f : defaultSize;
            float width = context.getResources().getDimension(widthId);
            Paint paint = new Paint();
            paint.setTextSize(baseSize);
            float widest = 0;
            for (String row : rows) widest = Math.max(widest, paint.measureText(row));
            // Retain XiaoAi's width fitting for unusually long codes.
            float scale = widest <= width ? 1f : Math.max(0.75f, width / widest);
            float textSize = baseSize * scale;
            views.setTextViewTextSize(titleId, TypedValue.COMPLEX_UNIT_PX, textSize);
            return textSize;
        }
        return 0;
    }

    @SuppressLint("DiscouragedApi")
    private static void fitExpandedTitle(Context context, RemoteViews views, int titleId,
                                          List<String> visible, float textSize) {
        TextView title = LayoutInflater.from(context).inflate(views.getLayoutId(), null)
                .findViewById(titleId);
        if (title == null) return;
        int widthId = context.getResources().getIdentifier("memory_scene_focused_text_width",
                "dimen", Constants.VOICE_ASSIST_PACKAGE);
        if (widthId == 0) return;
        String[] rows = PickupCodes.formatIslandText(visible).split("\n", -1);
        float width = context.getResources().getDimension(widthId);
        Paint actual = new Paint(title.getPaint());
        actual.setTextSize(textSize);
        float actualWidth = 0;
        for (String row : rows) actualWidth = Math.max(actualWidth, actual.measureText(row));
        // The expanded host is narrower than the notification template; reserve its inset.
        float available = width - 4 * context.getResources().getDisplayMetrics().density
                - title.getCompoundPaddingLeft() - title.getCompoundPaddingRight();
        float scaleX = actualWidth <= available ? 1f : available / actualWidth;
        views.setFloat(titleId, "setTextScaleX", scaleX);
        Log.d(Constants.LOG_TAG, "Pickup expanded title fit: width=" + available
                + " textWidth=" + actualWidth + " scaleX=" + scaleX);
    }
}
