package hook.HyperBackscreen.hook.subscreen;

import static hook.HyperBackscreen.hook.util.HookReflection.MAX_THEME_INDEX_BYTES;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethodCompatible;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethodQuietly;
import static hook.HyperBackscreen.hook.util.HookReflection.defaultReturnFor;
import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethodWithFirstParam;
import static hook.HyperBackscreen.hook.util.HookReflection.findFirstDeclaredMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.getFieldValue;
import static hook.HyperBackscreen.hook.util.HookReflection.getStaticFieldValueByType;
import static hook.HyperBackscreen.hook.util.HookReflection.isEmpty;
import static hook.HyperBackscreen.hook.util.HookReflection.readUtf8;
import static hook.HyperBackscreen.hook.util.HookReflection.setFieldValue;
import static hook.HyperBackscreen.hook.util.HookReflection.voidOnly;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.panel.SwipePanelHost;
import hook.HyperBackscreen.batteryring.BatteryRingHost;
import hook.HyperBackscreen.hook.target.MusicWhitelistShape;
import hook.HyperBackscreen.hook.target.SubScreenTargets;
import hook.HyperBackscreen.hook.target.TargetMembers;
import hook.HyperBackscreen.hook.target.TargetResolver;
import hook.HyperBackscreen.hook.ModuleMain;
import hook.HyperBackscreen.hook.appindex.PanelAppCard;
import hook.HyperBackscreen.hook.appindex.SubscreenAppIndex;

/**
 * 背屏中心（{@code com.xiaomi.subscreencenter}）进程里的 Hook：长按编辑拦截、应用卡功能开关与
 * Secure 设置、应用卡数量上限、应用卡列表缓存刷新与监听器通知、模块卡片点击与快捷面板手势分发、
 * 背屏壁纸选择同步到 Secure 设置、定时自动换壁纸。
 *
 * <p>appInfo.json 的读改写交给 {@link SubscreenAppIndex}。
 */
public final class SubScreenCenterHooks {
    private final ModuleMain module;
    private final SubscreenAppIndex appIndex;

    private static final long REAR_SELECTION_PENDING_TIMEOUT_MS = 15_000L;
    private final Map<View, PendingRearSelection> pendingRearSelections =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 随机切换用；只有宿主主线程 tick 会碰它，不需要额外同步。 */
    private final Random wallpaperRandom = new Random();
    private volatile long lastSubscreenAppInfoModified = Long.MIN_VALUE;
    /**
     * 官方应用卡同步的节流：应用列表取值方法 c() 调用极其频繁（容量检查、编辑、刷新都会调），
     * 每次都整套同步会在宿主主线程反复读写文件。索引文件没变时，[OFFICIAL_SYNC_MIN_INTERVAL_MS]
     * 内只同步一次。
     */
    private static final long OFFICIAL_SYNC_MIN_INTERVAL_MS = 3_000L;
    private volatile long lastOfficialSyncElapsed = Long.MIN_VALUE / 2;
    private volatile long lastOfficialSyncIndexStamp = Long.MIN_VALUE;
    private volatile long lastSubscreenAppInfoLength = Long.MIN_VALUE;
    private volatile int lastSubscreenAppInfoCount = Integer.MIN_VALUE;
    /** 用户追加的音乐应用名单（按原始串缓存解析结果）。 */
    private final MusicWidgetWhitelist musicWidgetWhitelist = new MusicWidgetWhitelist();
    /** insertAppWidget 交易期间置位，供应用卡列表 getter 判断是否需要封顶 size。 */
    private static final ThreadLocal<Boolean> APP_CARD_INSERT_ACTIVE = new ThreadLocal<>();

    public SubScreenCenterHooks(@NonNull ModuleMain module, @NonNull SubscreenAppIndex appIndex) {
        this.module = module;
        this.appIndex = appIndex;
    }

    public void installLongPressHooks(@NonNull ClassLoader classLoader) {
        Class<?> gestureClass = findLongPressGestureClass(classLoader);
        if (gestureClass != null) {
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_GATE_NEW, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_LONG_PRESS_TOUCH_NEW, new Class[]{MotionEvent.class}, null);
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Long press hook target resolved: " + gestureClass.getName());
        }

        Class<?> legacyGestureClass = findClass(Constants.HOOK_CLASS, classLoader);
        // 旧版类名同样会被让给无关类：只有「门控方法返回 boolean + 无参 run()」都对得上才挂。
        if (legacyGestureClass != null && !isLegacyLongPressGestureClass(legacyGestureClass)) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Legacy long press class shape mismatch, skipped: " + legacyGestureClass.getName());
            legacyGestureClass = null;
        }
        if (legacyGestureClass != null && legacyGestureClass != gestureClass) {
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_GATE, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
        }

        if (gestureClass == null && legacyGestureClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Long press hook targets missing: "
                            + Constants.HOOK_CLASS_LONG_PRESS_18PRO + ", "
                            + Constants.HOOK_CLASS_LONG_PRESS_OS4 + ", "
                            + Constants.HOOK_CLASS_LONG_PRESS_NEW + ", "
                            + Constants.HOOK_CLASS);
        }
    }

    private static boolean isLegacyLongPressGestureClass(@NonNull Class<?> candidate) {
        Method gate = findDeclaredMethod(candidate, Constants.HOOK_METHOD_GATE, MotionEvent.class);
        Method run = findDeclaredMethod(candidate, Constants.HOOK_METHOD_RUN);
        return gate != null && gate.getReturnType() == boolean.class
                && run != null && run.getReturnType() == void.class;
    }

    /**
     * 长按手势类的混淆名逐版本重排（Z1.v → k2.s → U1.C）。只按名字取类，会在名字被
     * 让给别的类时静默装到错误目标上，所以逐个候选校验三个 hook 方法是否齐全。
     */
    @Nullable
    private static Class<?> findLongPressGestureClass(@NonNull ClassLoader classLoader) {
        String[] classNames = {
                Constants.HOOK_CLASS_LONG_PRESS_18PRO,
                Constants.HOOK_CLASS_LONG_PRESS_OS4,
                Constants.HOOK_CLASS_LONG_PRESS_NEW
        };
        for (String className : classNames) {
            Class<?> candidate = findClass(className, classLoader);
            if (candidate == null) continue;
            if (findDeclaredMethod(candidate, Constants.HOOK_METHOD_GATE_NEW, MotionEvent.class) == null) continue;
            if (findDeclaredMethod(candidate, Constants.HOOK_METHOD_LONG_PRESS_TOUCH_NEW, MotionEvent.class) == null) continue;
            if (findDeclaredMethod(candidate, Constants.HOOK_METHOD_RUN) == null) continue;
            return candidate;
        }
        return null;
    }

    public void installSubscreenAppWidgetGateHooks(@NonNull ClassLoader classLoader) {
        Class<?> deviceConfigClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.DEVICE_CONFIG);
        if (deviceConfigClass != null) {
            module.hookBooleanTrueIfPresent(
                    deviceConfigClass,
                    Constants.SUBSCREEN_DEVICE_CONFIG_CLASS,
                    Constants.SUBSCREEN_DEVICE_CONFIG_APP_WIDGET_METHOD);
        }

        Class<?> guideSettingsClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.GUIDE_SETTINGS);
        if (guideSettingsClass != null) {
            module.hookMethodIfPresent(
                    guideSettingsClass,
                    Constants.SUBSCREEN_GUIDE_HIDDEN_METHOD,
                    new Class[]{String.class},
                    Constants.SUBSCREEN_GUIDE_SETTINGS_CLASS + "#"
                            + Constants.SUBSCREEN_GUIDE_HIDDEN_METHOD,
                    chain -> {
                        Object key = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        if (Constants.SUBSCREEN_APP_CARD_GUIDE_KEY.equals(key)
                                && PrefsBridge.shouldEnable18ProFeatures(module)) {
                            return false;
                        }
                        return chain.proceed();
                    }
            );
        } else {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "App widget guide hook target missing: "
                            + Constants.SUBSCREEN_GUIDE_SETTINGS_CLASS);
        }

        hookSubscreenAppWidgetSecureSetting();
        hookSubscreenLauncherPanelGesture(classLoader);
    }

    /**
     * 背屏应用卡上限由 SubScreenService 的 Binder 在 insertAppWidget 交易里用 15 判定，超限回 -2。
     * 列表 getter 返回内部列表的副本，因此只在这次交易期间把副本的 size 封顶到 14：判定放行后
     * 新增项与保存都作用在同一副本上，落盘数据完整。
     */
    public void installAppCardLimitHook(@NonNull ClassLoader classLoader) {
        Class<?> binderClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.SERVICE_BINDER);
        Class<?> listManagerClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.APP_LIST_MANAGER);
        if (binderClass == null || listManagerClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "App card limit targets missing: "
                    + Constants.SUBSCREEN_SERVICE_BINDER_CLASS + ", "
                    + Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS);
            return;
        }

        module.hookMethodIfPresent(
                findDeclaredMethod(binderClass, Constants.SUBSCREEN_ON_TRANSACT_METHOD,
                        int.class, Parcel.class, Parcel.class, int.class),
                Constants.SUBSCREEN_SERVICE_BINDER_CLASS + "#" + Constants.SUBSCREEN_ON_TRANSACT_METHOD,
                chain -> {
                    Object transaction = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!(transaction instanceof Integer)
                            || (Integer) transaction != Constants.SUBSCREEN_INSERT_APP_WIDGET_TRANSACTION) {
                        return chain.proceed();
                    }
                    APP_CARD_INSERT_ACTIVE.set(Boolean.TRUE);
                    try {
                        return chain.proceed();
                    } finally {
                        APP_CARD_INSERT_ACTIVE.remove();
                    }
                }
        );

        module.hookMethodIfPresent(
                findDeclaredMethod(listManagerClass, Constants.SUBSCREEN_APP_LIST_GETTER_METHOD),
                Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS + "#"
                        + Constants.SUBSCREEN_APP_LIST_GETTER_METHOD,
                chain -> {
                    refreshSubscreenAppInfoCacheIfChanged(classLoader, chain.getThisObject());
                    Object list = chain.proceed();
                    if (!(list instanceof ArrayList)) return list;
                    ArrayList<?> items = (ArrayList<?>) list;
                    if (Boolean.TRUE.equals(APP_CARD_INSERT_ACTIVE.get())
                            && PrefsBridge.shouldEnableAppCard(module)
                            && PrefsBridge.shouldRemoveAppCardLimit(module)
                            && items.size() >= Constants.SUBSCREEN_APP_CARD_LIMIT) {
                        return new SizeCappedList(items, Constants.SUBSCREEN_APP_CARD_LIMIT - 1);
                    }
                    // c() also feeds Binder capacity checks and edits. UI-only entries belong in d().
                    return list;
                }
        );

        // 背屏上滑面板通过这条分发链取数据；进入前先同步官方索引并刷新宿主缓存。
        module.hookMethodIfPresent(
                findDeclaredMethod(listManagerClass,
                        Constants.SUBSCREEN_APP_LIST_DISPATCH_METHOD, Consumer.class),
                Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS + "#"
                        + Constants.SUBSCREEN_APP_LIST_DISPATCH_METHOD,
                chain -> {
                    refreshSubscreenAppInfoCacheIfChanged(classLoader, chain.getThisObject());
                    return chain.proceed();
                }
        );

        // 保存路径按 size() 索引遍历，封顶副本要先还原成普通列表，否则尾部条目会被截掉。
        module.hookMethodIfPresent(
                findDeclaredMethodWithFirstParam(
                        listManagerClass,
                        Constants.SUBSCREEN_APP_LIST_SAVE_METHOD,
                        ArrayList.class),
                Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS + "#"
                        + Constants.SUBSCREEN_APP_LIST_SAVE_METHOD,
                chain -> {
                    Object list = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!(list instanceof SizeCappedList)) {
                        return chain.proceed();
                    }
                    List<Object> args = new ArrayList<>(chain.getArgs());
                    args.set(0, ((SizeCappedList) list).fullCopy());
                    return chain.proceed(args.toArray());
                }
        );

        module.log(Log.INFO, Constants.LOG_TAG, "App card limit hook installed");
    }

    private void refreshSubscreenAppInfoCacheIfChanged(
            @NonNull ClassLoader classLoader,
            @Nullable Object listManager
    ) {
        if (listManager == null) return;
        File indexFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        long indexStamp = indexFile.isFile() ? indexFile.lastModified() ^ (indexFile.length() << 20) : 0L;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (indexStamp != lastOfficialSyncIndexStamp
                || nowElapsed - lastOfficialSyncElapsed >= OFFICIAL_SYNC_MIN_INTERVAL_MS) {
            appIndex.syncOfficialPanelAppCardWithSubscreenIndex();
            appIndex.cleanupDeletedSubscreenAppInfoEntries();
            lastOfficialSyncElapsed = nowElapsed;
            lastOfficialSyncIndexStamp = indexFile.isFile()
                    ? indexFile.lastModified() ^ (indexFile.length() << 20) : 0L;
        }
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        if (!appInfoFile.isFile()) return;

        long modified = appInfoFile.lastModified();
        long length = appInfoFile.length();
        if (modified == lastSubscreenAppInfoModified
                && length == lastSubscreenAppInfoLength) {
            return;
        }

        try {
            // 只在确认是宿主的列表管理器实例时才写回，防止混淆名漂移后把列表塞进无关对象。
            Class<?> listManagerClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.APP_LIST_MANAGER);
            if (listManagerClass == null || !listManagerClass.isInstance(listManager)) return;
            String listFieldName = TargetMembers.fieldName(
                    TargetMembers.resolveField(listManager.getClass(), ArrayList.class, "c"), "c");
            Object current = getFieldValue(listManager, listFieldName);
            ArrayList<Object> items = readSubscreenAppInfoList(
                    classLoader, appInfoFile, current instanceof List<?> list ? list : null);
            if (items == null) return;
            if (setFieldValue(listManager, listFieldName, items)) {
                lastSubscreenAppInfoModified = modified;
                lastSubscreenAppInfoLength = length;
                lastSubscreenAppInfoCount = items.size();
                notifySubscreenAppInfoListeners(classLoader, listManager, items, "refresh");
                module.log(Log.INFO, Constants.LOG_TAG,
                        "Refreshed SubScreenCenter app list cache: size=" + items.size());
            }
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "refreshSubscreenAppInfoCacheIfChanged failed", e);
        }
    }

    private void refreshActiveSubscreenAppInfoCache(@NonNull ClassLoader classLoader) {
        Class<?> listManagerClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.APP_LIST_MANAGER);
        if (listManagerClass == null) return;
        Object listManager = getStaticFieldValueByType(listManagerClass, listManagerClass);
        refreshSubscreenAppInfoCacheIfChanged(classLoader, listManager);
    }

    private void notifySubscreenAppInfoListeners(
            @NonNull ClassLoader classLoader,
            @NonNull Object listManager,
            @NonNull ArrayList<Object> items,
            @NonNull String source
    ) {
        String listenersFieldName = TargetMembers.fieldName(
                TargetMembers.resolveField(listManager.getClass(), HashSet.class, "a"), "a");
        Object listenersValue = getFieldValue(listManager, listenersFieldName);
        if (!(listenersValue instanceof Collection<?> listeners) || listeners.isEmpty()) return;
        ArrayList<Object> payload = new ArrayList<>(items);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 监听器会直接刷新宿主 UI；从 Binder 线程进来时切回主线程再通知。
            ArrayList<Object> snapshot = new ArrayList<>(listeners);
            new Handler(Looper.getMainLooper()).post(() ->
                    notifySubscreenAppInfoListenersNow(classLoader, snapshot, payload, source));
            return;
        }
        notifySubscreenAppInfoListenersNow(classLoader, new ArrayList<>(listeners), payload, source);
    }

    private void notifySubscreenAppInfoListenersNow(
            @NonNull ClassLoader classLoader,
            @NonNull List<?> listeners,
            @NonNull ArrayList<Object> payload,
            @NonNull String source
    ) {
        Class<?> entryClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.LAUNCHER_ENTRY);
        if (entryClass == null) return;
        int notified = 0;
        for (Object listener : listeners) {
            if (listener == null) continue;
            Method callback = TargetMembers.resolveMethod(
                    listener.getClass(), void.class,
                    new Class<?>[]{ArrayList.class, String.class, entryClass}, "a");
            if (callback == null) continue;
            try {
                callback.invoke(listener, new ArrayList<>(payload), source, null);
                notified++;
            } catch (Throwable e) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "SubScreenCenter app list listener notify failed", e);
            }
        }
        if (notified > 0) {
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Notified SubScreenCenter app list listeners: count=" + notified
                            + ", size=" + payload.size());
        }
    }

    /**
     * 按 appInfo.json 重建宿主列表。宿主内存里已有的同一条目（resId + mtzPath 相同）原样复用，
     * 保留宿主自己填的多语言名称、Intent 等字段；只有文件里新出现的条目才按 JSON 构造。
     */
    @Nullable
    private ArrayList<Object> readSubscreenAppInfoList(
            @NonNull ClassLoader classLoader,
            @NonNull File appInfoFile,
            @Nullable List<?> currentItems
    ) {
        try {
            Class<?> entryClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.LAUNCHER_ENTRY);
            if (entryClass == null) return null;
            Constructor<?> constructor = entryClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            HashMap<String, ArrayDeque<Object>> existing = new HashMap<>();
            if (currentItems != null) {
                for (Object old : currentItems) {
                    if (!entryClass.isInstance(old)) continue;
                    existing.computeIfAbsent(entryKey(
                                    getFieldValue(old, Constants.SUBSCREEN_LAUNCHER_ENTRY_RES_ID_FIELD),
                                    getFieldValue(old, Constants.SUBSCREEN_LAUNCHER_ENTRY_MTZ_FIELD)),
                            k -> new ArrayDeque<>()).add(old);
                }
            }
            JSONArray array = new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES));
            ArrayList<Object> items = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                ArrayDeque<Object> reusable = existing.get(entryKey(
                        item.optString("resId", null), item.optString("mtzPath", null)));
                Object reused = reusable == null ? null : reusable.poll();
                if (reused != null) {
                    items.add(reused);
                    continue;
                }
                Object entry = constructor.newInstance();
                String appNameSetter = TargetMembers.methodName(
                        TargetMembers.resolveMethod(
                                entryClass, void.class, new Class<?>[]{String.class}, "c"), "c");
                callMethodQuietly(entry, appNameSetter, item.optString("appName", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_MTZ_FIELD,
                        item.optString("mtzPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_BIND_APP_FIELD,
                        item.optString("bindApp", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_ICON_FIELD,
                        item.optString("appIconPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_FIELD,
                        item.optString("previewLightPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_FIELD,
                        item.optString("previewDarkPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_RES_ID_FIELD,
                        item.optString("resId", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_GAME_FIELD,
                        item.optBoolean("isGame", false));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_CARD_TYPE_FIELD,
                        item.optInt("appCardType", -1));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PRESET_FIELD,
                        item.optBoolean("isPreset", false));
                items.add(entry);
            }
            return items;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "readSubscreenAppInfoList failed", e);
            return null;
        }
    }

    @NonNull
    private static String entryKey(@Nullable Object resId, @Nullable Object mtzPath) {
        return (resId == null ? "" : resId.toString()) + "\u0000" + (mtzPath == null ? "" : mtzPath.toString());
    }

    /**
     * 模块卡片的点击。
     *
     * 宿主 {@code e2.l.onClick} 对 {@code k != 3} 的条目直接 {@code startActivity(item.m)}，
     * 那会把模块设置页开到主屏上；这里改成在背屏当前窗口里展开快捷面板，和原来的上滑面板
     * 是同一套 UI，只是入口换成卡片。
     */
    public void installModuleEntryClickHook(@NonNull ClassLoader classLoader) {
        Class<?> holderClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.LAUNCHER_HOLDER);
        if (holderClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Module entry click target missing: " + Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS);
            return;
        }
        Method clickMethod = findDeclaredMethod(
                holderClass, Constants.SUBSCREEN_LAUNCHER_HOLDER_CLICK_METHOD, View.class);
        if (clickMethod == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Module entry click method missing: "
                    + Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS);
            return;
        }
        module.hookMethodIfPresent(
                clickMethod,
                Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS + "#"
                        + Constants.SUBSCREEN_LAUNCHER_HOLDER_CLICK_METHOD,
                chain -> {
                    Object item = getFieldValue(chain.getThisObject(),
                            Constants.SUBSCREEN_LAUNCHER_HOLDER_ITEM_FIELD);
                    if (!PanelAppCard.isModuleEntry(classLoader, item)) return chain.proceed();
                    if (!PrefsBridge.shouldEnableAppCard(module)) return null;
                    Object clicked = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    Activity activity = resolveHostActivity(clicked);
                    if (activity == null) return chain.proceed();
                    if (SwipePanelHost.shouldSuppressModuleCardClick()) {
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Quick panel card click ignored after launcher swipe");
                        return null;
                    }
                    SwipePanelHost.show(activity, clicked instanceof View ? (View) clicked : null);
                    module.log(Log.INFO, Constants.LOG_TAG, "Quick panel opened from card entry");
                    return null;
                }
        );
        module.log(Log.INFO, Constants.LOG_TAG, "Module entry click hook installed");
    }

    public void installQuickPanelDispatchHook(@NonNull ClassLoader classLoader) {
        Class<?> launcherClass = findClass(Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS, classLoader);
        if (launcherClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Quick panel dispatch target missing: "
                            + Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS);
            return;
        }
        for (String lifecycle : new String[]{"onPause", "onDestroy"}) {
            module.hookMethodIfPresent(findDeclaredMethod(launcherClass, lifecycle),
                    Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS + "#" + lifecycle, chain -> {
                        SwipePanelHost.release((Activity) chain.getThisObject());
                        BatteryRingHost.release((Activity) chain.getThisObject());
                        return chain.proceed();
                    });
        }
        module.hookMethodIfPresent(findDeclaredMethod(launcherClass, "onResume"),
                Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS + "#onResume", chain -> {
                    Object result = chain.proceed();
                    refreshActiveSubscreenAppInfoCache(classLoader);
                    BatteryRingHost.show((Activity) chain.getThisObject(), module);
                    return result;
                });
        module.hookMethodIfPresent(
                findDeclaredMethod(launcherClass, "dispatchTouchEvent", MotionEvent.class),
                Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS + "#dispatchTouchEvent",
                chain -> {
                    Object event = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (event instanceof MotionEvent) {
                        Object host = chain.getThisObject();
                        SwipePanelHost.trackLauncherTouch(
                                (MotionEvent) event,
                                host instanceof Context ? (Context) host : null);
                        if (SwipePanelHost.handleLauncherGesture((MotionEvent) event)) {
                            return true;
                        }
                    }
                    return chain.proceed();
                }
        );
    }

    /** 背屏面板挂在宿主 Activity 的窗口上，所以要从点击到的 View 反查 Activity。 */
    @Nullable
    private static Activity resolveHostActivity(@Nullable Object view) {
        if (!(view instanceof View)) return null;
        Context context = ((View) view).getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private void hookSubscreenAppWidgetSecureSetting() {
        module.hookMethodIfPresent(
                findDeclaredMethod(Settings.Secure.class, "getInt",
                        ContentResolver.class, String.class, int.class),
                "Settings.Secure#getInt(ContentResolver,String,int)",
                chain -> PrefsBridge.shouldEnable18ProFeatures(module)
                        && AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                        chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null)
                        ? 1
                        : chain.proceed()
        );
        module.hookMethodIfPresent(
                findDeclaredMethod(Settings.Secure.class, "getInt",
                        ContentResolver.class, String.class),
                "Settings.Secure#getInt(ContentResolver,String)",
                chain -> PrefsBridge.shouldEnable18ProFeatures(module)
                        && AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                        chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null)
                        ? 1
                        : chain.proceed()
        );
    }

    private void hookSubscreenLauncherPanelGesture(@NonNull ClassLoader classLoader) {
        Class<?> gestureClass = TargetResolver.resolveClass(
                classLoader, SubScreenTargets.LAUNCHER_GESTURE);
        if (gestureClass == null) {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "App widget gesture hook target missing: "
                            + Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_CLASS);
            return;
        }
        Method gestureMethod = findDeclaredMethod(
                gestureClass,
                Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_METHOD,
                MotionEvent.class);
        final boolean gestureReturnsBoolean = gestureMethod != null
                && (gestureMethod.getReturnType() == boolean.class
                || gestureMethod.getReturnType() == Boolean.class);
        module.hookMethodIfPresent(
                gestureMethod,
                Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_CLASS + "#"
                        + Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_METHOD,
                chain -> {
                    if (!PrefsBridge.shouldEnable18ProFeatures(module)) return chain.proceed();
                    Object event = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (event instanceof MotionEvent
                            && SwipePanelHost.handleLauncherGesture((MotionEvent) event)) {
                        return gestureReturnsBoolean ? true : null;
                    }
                    setFieldValue(
                            chain.getThisObject(),
                            Constants.SUBSCREEN_LAUNCHER_PANEL_WIDGET_ENABLED_FIELD,
                            true);
                    return chain.proceed();
                }
        );
    }

    /**
     * 背屏长按选择壁纸后，宿主只把所选下标保存到自己的 user_pref.json。
     * 系统设置页不读这个下标，而是直接预览 Secure Settings 中 theme_rear_widget
     * 的第一项，因此两边会长期显示不同壁纸。
     */
    public void installRearScreenSelectionSyncHook(@NonNull ClassLoader classLoader) {
        Class<?> mainPanelClass = findClass(Constants.HOOK_CLASS_MAIN_PANEL, classLoader);
        if (mainPanelClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Rear selection sync target missing: " + Constants.HOOK_CLASS_MAIN_PANEL);
            return;
        }

        module.hookMethodIfPresent(
                voidOnly(findFirstDeclaredMethod(
                        mainPanelClass,
                        new Class[]{boolean.class},
                        Constants.HOOK_METHOD_REQUEST_EXIT_EDIT_18PRO,
                        Constants.HOOK_METHOD_REQUEST_EXIT_EDIT)),
                Constants.HOOK_CLASS_MAIN_PANEL + "#["
                        + Constants.HOOK_METHOD_REQUEST_EXIT_EDIT_18PRO + ", "
                        + Constants.HOOK_METHOD_REQUEST_EXIT_EDIT + "]",
                chain -> {
                    View panel = chain.getThisObject() instanceof View
                            ? (View) chain.getThisObject()
                            : null;
                    Object cancelValue = chain.getArgs().isEmpty()
                            ? null
                            : chain.getArgs().get(0);
                    boolean cancel = Boolean.TRUE.equals(cancelValue);

                    if (panel == null || cancel || !PrefsBridge.shouldFixRearScreenApply(module)) {
                        if (panel != null) pendingRearSelections.remove(panel);
                        return chain.proceed();
                    }

                    PendingRearSelection pending = capturePendingRearSelection(panel);
                    if (pending == null) {
                        pendingRearSelections.remove(panel);
                    } else {
                        pendingRearSelections.put(panel, pending);
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear selection commit requested: id=" + pending.wallpaperId
                                        + ", index=" + pending.selectedIndex);
                    }
                    return chain.proceed();
                }
        );

        module.hookMethodIfPresent(
                voidOnly(findFirstDeclaredMethod(
                        mainPanelClass,
                        new Class[]{},
                        Constants.HOOK_METHOD_SAVE_USER_SELECTION_18PRO,
                        Constants.HOOK_METHOD_SAVE_USER_SELECTION)),
                Constants.HOOK_CLASS_MAIN_PANEL + "#["
                        + Constants.HOOK_METHOD_SAVE_USER_SELECTION_18PRO + ", "
                        + Constants.HOOK_METHOD_SAVE_USER_SELECTION + "]",
                chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View panel) {
                        PendingRearSelection pending = pendingRearSelections.get(panel);
                        if (PrefsBridge.shouldFixRearScreenApply(module)
                                || (pending != null && pending.autoRotation)) {
                            consumePendingRearSelection(panel);
                        } else {
                            pendingRearSelections.remove(panel);
                        }
                        AutoWallpaperRotationHost.onWallpaperCommitted(panel);
                    }
                    return result;
                }
        );
    }

    /**
     * 背屏音乐控件白名单：背屏中心只对云控名单里的音乐应用显示背屏音乐卡片
     * （SmartAssistantManager 日志 "Unsupported music app: %s, removing widget"）。
     * 判定类的 c(String) 对用户名单里的包返回 true，b() 的返回集合并入用户名单。
     * 名单为空时两个 Hook 都直接走原逻辑。改名单后要重启背屏中心才会重新拉卡片。
     */
    public void installMusicWidgetWhitelistHooks(@NonNull ClassLoader classLoader) {
        try {
            Class<?> whitelistClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.MUSIC_WHITELIST);
            if (whitelistClass == null) {
                module.log(Log.WARN, Constants.LOG_TAG, "Music widget whitelist target missing");
                return;
            }
            String label = whitelistClass.getName();
            Method memberCheck = MusicWhitelistShape.memberCheckMethod(whitelistClass);
            Method whitelist = MusicWhitelistShape.whitelistMethod(whitelistClass);
            module.hookMethodIfPresent(
                    memberCheck,
                    label + "#" + (memberCheck == null ? "?" : memberCheck.getName()) + "(String)",
                    chain -> {
                        Object packageName = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        Set<String> extra = musicWidgetWhitelist.packages(
                                PrefsBridge.readMusicWidgetWhitelistPackagesForHook(module));
                        if (MusicWidgetWhitelist.isExtraMusicApp(extra, packageName)) return true;
                        return chain.proceed();
                    }
            );
            module.hookMethodIfPresent(
                    whitelist,
                    label + "#" + (whitelist == null ? "?" : whitelist.getName()) + "()",
                    chain -> {
                        Object original = chain.proceed();
                        Set<String> extra = musicWidgetWhitelist.packages(
                                PrefsBridge.readMusicWidgetWhitelistPackagesForHook(module));
                        return MusicWidgetWhitelist.merge(original, extra);
                    }
            );
        } catch (Throwable throwable) {
            module.log(Log.WARN, Constants.LOG_TAG, "Music widget whitelist hook failed", throwable);
        }
    }

    public void installAutoWallpaperRotationHooks(@NonNull ClassLoader classLoader) {
        Class<?> mainPanelClass = findClass(Constants.HOOK_CLASS_MAIN_PANEL, classLoader);
        if (mainPanelClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Auto wallpaper target missing: " + Constants.HOOK_CLASS_MAIN_PANEL);
            return;
        }

        module.hookMethodIfPresent(
                voidOnly(findDeclaredMethod(mainPanelClass, Constants.HOOK_METHOD_MAIN_PANEL_RESUME)),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_RESUME,
                chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.onPanelResumed(
                                panel,
                                module,
                                this::rotateToNextRearWallpaper);
                    }
                    return result;
                });
        module.hookMethodIfPresent(
                voidOnly(findDeclaredMethod(mainPanelClass, Constants.HOOK_METHOD_MAIN_PANEL_PAUSE)),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_PAUSE,
                chain -> {
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.onPanelPaused(panel);
                    }
                    return chain.proceed();
                });
        module.hookMethodIfPresent(
                voidOnly(findDeclaredMethod(mainPanelClass, Constants.HOOK_METHOD_MAIN_PANEL_DESTROY)),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_DESTROY,
                chain -> {
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.release(panel);
                    }
                    return chain.proceed();
                });
        module.hookMethodIfPresent(
                voidOnly(findDeclaredMethod(
                        mainPanelClass,
                        Constants.HOOK_METHOD_MAIN_PANEL_AOD_CHANGED,
                        boolean.class)),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_AOD_CHANGED,
                chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.onPanelStateChanged(panel);
                    }
                    return result;
                });
    }

    private boolean rotateToNextRearWallpaper(@NonNull View panel, @NonNull String order) {
        if (SwipePanelHost.isShowing()) return false;
        Object editing = getFieldValue(panel, Constants.HOOK_FIELD_MAIN_PANEL_EDITING);
        if (Boolean.TRUE.equals(editing)) return false;
        Object state = getFieldValue(panel, Constants.HOOK_FIELD_MAIN_PANEL_STATE);
        if (state instanceof Number && ((Number) state).intValue() != 0) return false;

        Object listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4);
        if (!(listValue instanceof List<?>)) {
            listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST);
        }
        if (!(listValue instanceof List<?> widgets) || widgets.size() < 2) return false;

        Integer currentIndex = resolveWallpaperIndex(panel, false);
        if (currentIndex == null) return false;
        int nextIndex = AutoWallpaperRotationPolicy.nextIndex(
                currentIndex,
                widgets.size(),
                order,
                wallpaperRandom);
        if (nextIndex < 0) return false;
        Integer nextId = resolveWallpaperIdAtIndex(panel, nextIndex);
        if (nextId == null) return false;

        PendingRearSelection pending = new PendingRearSelection(
                nextId,
                nextIndex,
                SystemClock.elapsedRealtime(),
                true);
        pendingRearSelections.put(panel, pending);
        try {
            callMethodCompatible(
                    panel,
                    Constants.HOOK_METHOD_AUTO_WALLPAPER_SWITCH,
                    nextIndex,
                    widgets);
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Auto wallpaper switch requested: id=" + nextId
                            + ", index=" + currentIndex + "->" + nextIndex);
            return true;
        } catch (Throwable error) {
            pendingRearSelections.remove(panel);
            module.log(Log.WARN, Constants.LOG_TAG, "Auto wallpaper switch failed", error);
            return false;
        }
    }

    @Nullable
    private PendingRearSelection capturePendingRearSelection(@NonNull View panel) {
        Integer committedId = resolveWallpaperId(panel, false);
        Integer previewId = resolveWallpaperId(panel, true);
        Integer previewIndex = resolveWallpaperIndex(panel, true);
        if (committedId == null || previewId == null || previewIndex == null
                || committedId.intValue() == previewId.intValue()) {
            return null;
        }
        return new PendingRearSelection(
                previewId,
                previewIndex,
                SystemClock.elapsedRealtime(),
                false);
    }

    private void consumePendingRearSelection(@NonNull View panel) {
        PendingRearSelection pending = pendingRearSelections.get(panel);
        if (pending == null) return;

        long age = SystemClock.elapsedRealtime() - pending.requestedAtElapsed;
        if (age < 0L || age > REAR_SELECTION_PENDING_TIMEOUT_MS) {
            pendingRearSelections.remove(panel);
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Ignored expired rear selection commit: id=" + pending.wallpaperId
                            + ", ageMs=" + age);
            return;
        }

        Integer committedId = resolveWallpaperId(panel, false);
        if (committedId == null || committedId.intValue() != pending.wallpaperId) {
            pendingRearSelections.remove(panel);
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Ignored mismatched rear selection commit: pendingId=" + pending.wallpaperId
                            + ", committedId=" + committedId);
            return;
        }

        pendingRearSelections.remove(panel);
        syncSelectedWallpaperToSettings(panel, pending.wallpaperId, pending.selectedIndex);
    }

    @Nullable
    private Integer resolveWallpaperId(@NonNull View panel, boolean preview) {
        Object os4ListValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4);
        boolean os4Layout = os4ListValue instanceof List<?>;
        Object listValue = os4Layout
                ? os4ListValue
                : getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST);
        if (!(listValue instanceof List<?> widgets)) return null;

        Integer index = resolveWallpaperIndex(panel, preview, os4Layout);
        if (index == null || index < 0 || index >= widgets.size()) return null;
        return resolveWallpaperIdAtIndex(widgets, index);
    }

    @Nullable
    private Integer resolveWallpaperIdAtIndex(@NonNull View panel, int index) {
        Object listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4);
        if (!(listValue instanceof List<?>)) {
            listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST);
        }
        return listValue instanceof List<?> widgets
                ? resolveWallpaperIdAtIndex(widgets, index)
                : null;
    }

    @Nullable
    private Integer resolveWallpaperIdAtIndex(@NonNull List<?> widgets, int index) {
        if (index < 0 || index >= widgets.size()) return null;
        Object widget = widgets.get(index);
        Object bean = getFieldValue(widget, Constants.HOOK_FIELD_WIDGET_BEAN);
        Object idValue = getFieldValue(bean, Constants.HOOK_FIELD_WIDGET_ID);
        return idValue instanceof Number ? ((Number) idValue).intValue() : null;
    }

    @Nullable
    private Integer resolveWallpaperIndex(@NonNull View panel, boolean preview) {
        boolean os4Layout = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4)
                instanceof List<?>;
        return resolveWallpaperIndex(panel, preview, os4Layout);
    }

    @Nullable
    private Integer resolveWallpaperIndex(
            @NonNull View panel,
            boolean preview,
            boolean os4Layout
    ) {
        String fieldName;
        if (preview) {
            fieldName = os4Layout
                    ? Constants.HOOK_FIELD_PREVIEW_INDEX_OS4
                    : Constants.HOOK_FIELD_PREVIEW_INDEX;
        } else {
            fieldName = os4Layout
                    ? Constants.HOOK_FIELD_SELECTED_INDEX_OS4
                    : Constants.HOOK_FIELD_SELECTED_INDEX;
        }
        Object indexValue = getFieldValue(panel, fieldName);
        return indexValue instanceof Number ? ((Number) indexValue).intValue() : null;
    }

    private void syncSelectedWallpaperToSettings(
            @NonNull View panel,
            int selectedId,
            int selectedIndex
    ) {
        try {
            String raw = Settings.Secure.getString(
                    panel.getContext().getContentResolver(),
                    Constants.SECURE_THEME_REAR_WIDGET);
            if (isEmpty(raw)) return;

            JSONObject root = new JSONObject(raw);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) return;

            JSONObject selected = null;
            JSONArray remaining = new JSONArray();
            boolean needsWrite = false;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                if (item.optInt("id") == selectedId) {
                    selected = item;
                    if (i != 0 || !item.optBoolean("changed", false)) {
                        needsWrite = true;
                    }
                } else {
                    if (item.optBoolean("changed", false)) needsWrite = true;
                    item.put("changed", false);
                    remaining.put(item);
                }
            }
            if (selected == null) return;
            if (!needsWrite) {
                module.log(Log.DEBUG, Constants.LOG_TAG,
                        "Rear selection already synchronized: id=" + selectedId
                                + ", index=" + selectedIndex);
                return;
            }

            selected.put("changed", true);
            JSONArray reordered = new JSONArray();
            reordered.put(selected);
            for (int i = 0; i < remaining.length(); i++) {
                reordered.put(remaining.get(i));
            }
            root.put("data", reordered);
            root.put("updateTime", System.currentTimeMillis());

            if (Settings.Secure.putString(
                    panel.getContext().getContentResolver(),
                    Constants.SECURE_THEME_REAR_WIDGET,
                    root.toString())) {
                module.log(Log.INFO, Constants.LOG_TAG,
                        "Synced rear selection to Settings: id=" + selectedId
                                + ", index=" + selectedIndex);
            } else {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "Failed to write rear selection to Settings: id=" + selectedId);
            }
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "syncSelectedWallpaperToSettings failed", e);
        }
    }

    private static final class PendingRearSelection {
        final int wallpaperId;
        final int selectedIndex;
        final long requestedAtElapsed;
        final boolean autoRotation;

        PendingRearSelection(
                int wallpaperId,
                int selectedIndex,
                long requestedAtElapsed,
                boolean autoRotation
        ) {
            this.wallpaperId = wallpaperId;
            this.selectedIndex = selectedIndex;
            this.requestedAtElapsed = requestedAtElapsed;
            this.autoRotation = autoRotation;
        }
    }

    private void hookLongPressMethod(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @Nullable Object blockedReturn
    ) {
        Method method = findDeclaredMethod(targetClass, methodName, parameterTypes);
        // 拦截时按真实返回类型给默认值：基本类型返回 null 会被框架拆箱成异常。
        Object fallback = method == null ? blockedReturn : defaultReturnFor(method.getReturnType(), blockedReturn);
        module.hookMethodIfPresent(
                method,
                targetClass.getName() + "#" + methodName,
                chain -> {
                    if (PrefsBridge.shouldBlockLongPressEdit(module)) {
                        return fallback;
                    }
                    return chain.proceed();
                }
        );
    }
}
