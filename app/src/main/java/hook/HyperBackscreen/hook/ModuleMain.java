package hook.HyperBackscreen.hook;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.bridge.DiagnosticLogStore;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearScreenWakeMatcher;
import hook.HyperBackscreen.ui.SwipePanelHost;
import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private static final long REAR_SELECTION_PENDING_TIMEOUT_MS = 15_000L;
    private static final long THEME_DATABASE_SYNC_DEDUP_WINDOW_MS = 2_000L;
    private static final long MAX_EDIT_CONFIG_BYTES = 512 * 1024L;
    private static final String THEME_MAGIC_DIR = "/data/system/theme_magic";
    private static final String THEME_MAGIC_USERS_DIR = THEME_MAGIC_DIR + "/users/";
    private volatile boolean systemHooksInstalled = false;
    private volatile boolean hooksInstalled = false;
    private volatile boolean themeStoreHooksInstalled = false;
    private volatile boolean pickupHooksInstalled = false;
    private final Map<Activity, SwipeState> swipeStates = new WeakHashMap<>();
    private final Set<Activity> exclusionApplied = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> themeSettingsShortcutControllers =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<View, PendingRearSelection> pendingRearSelections =
            Collections.synchronizedMap(new WeakHashMap<>());
    @Nullable
    private volatile Integer lastThemeDatabaseSyncedWidgetId;
    private volatile long lastThemeDatabaseSyncedAtElapsed;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 供被 Hook 进程（背屏）内的 PrefsBridge 取远程偏好使用
        PrefsBridge.attachModule(this);
        log(Log.INFO, Constants.LOG_TAG, "onModuleLoaded: " + param.getProcessName());
        if (param.isSystemServer()) {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if (contextClassLoader != null) {
                installSystemHooksOnce(contextClassLoader);
            }
            installSystemHooksOnce(ClassLoader.getSystemClassLoader());
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        installSystemHooksOnce(param.getClassLoader());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();

        if (Constants.VOICE_ASSIST_PACKAGE.equals(packageName)) {
            synchronized (this) {
                if (!pickupHooksInstalled) {
                    pickupHooksInstalled = PickupCodeHook.install(this, param.getClassLoader());
                }
            }
            return;
        }

        if (Constants.SYSTEM_PACKAGE.equals(packageName)) {
            installSystemHooksOnce(param.getClassLoader());
            return;
        }

        if (Constants.TARGET_PACKAGE.equals(packageName)) {
            if (hooksInstalled) return;
            synchronized (this) {
                if (hooksInstalled) return;
                try {
                    SwipePanelHost.setLoadedModuleApkPath(getModuleApplicationInfo().sourceDir);
                    installLongPressHooks(param.getClassLoader());
                    installSwipePanelHook(param.getClassLoader());
                    installRearScreenSelectionSyncHook(param.getClassLoader());
                    hooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Hooks installed for " + Constants.TARGET_PACKAGE);
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG, "Failed to install hooks", throwable);
                }
            }
            return;
        }

        if (Constants.THEME_STORE_PACKAGE.equals(packageName)) {
            if (themeStoreHooksInstalled) return;
            synchronized (this) {
                if (themeStoreHooksInstalled) return;
                try {
                    installWallpaperLimitHook(param.getClassLoader());
                    installRearScreenApplyFixHook(param.getClassLoader());
                    installThemeSettingsSelectionSyncHook(param.getClassLoader());
                    installThemeSettingsShortcutHook(param.getClassLoader());
                    themeStoreHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Theme store hooks installed");
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG, "Failed to install theme store hooks", throwable);
                }
            }
        }

    }

    private void installSystemHooksOnce(@NonNull ClassLoader classLoader) {
        if (systemHooksInstalled) return;
        synchronized (this) {
            if (systemHooksInstalled) return;
            try {
                if (installSystemHooks(classLoader)) {
                    systemHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "System hooks installed");
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, Constants.LOG_TAG, "Failed to install system hooks", throwable);
            }
        }
    }

    private boolean installSystemHooks(@NonNull ClassLoader classLoader) {
        Class<?> coverManagerClass = findClass(Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS, classLoader);
        if (coverManagerClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "System hook target missing: " + Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS);
            return false;
        }
        Class<?> powerManagerServiceImplClass = findClass(
                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS,
                classLoader);

        boolean coverHookInstalled = hookMethodIfPresent(
                coverManagerClass,
                Constants.SYSTEM_SHOW_COVER_VIEW_METHOD,
                new Class[]{int.class},
                Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS + "#"
                        + Constants.SYSTEM_SHOW_COVER_VIEW_METHOD,
                chain -> {
                    Object displayIdValue = chain.getArgs().get(0);
                    if (displayIdValue instanceof Integer
                            && ((Integer) displayIdValue) == 1
                            && PrefsBridge.shouldDisableRearScreenCover(this)) {
                        log(Log.DEBUG, Constants.LOG_TAG, "Rear screen cover skipped");
                        return null;
                    }
                    return chain.proceed();
                }
        );

        boolean powerWakeHookInstalled = hookMethodIfPresent(
                powerManagerServiceImplClass == null
                        ? null
                        : findDeclaredMethod(
                                powerManagerServiceImplClass,
                                Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                                int.class,
                                String.class,
                                int.class),
                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS + "#"
                        + Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                chain -> {
                    Object groupIdValue = chain.getArgs().get(0);
                    Object detailsValue = chain.getArgs().get(1);
                    if (groupIdValue instanceof Integer
                            && RearScreenWakeMatcher.isRearDoubleTapWake(
                                    (Integer) groupIdValue,
                                    detailsValue)) {
                        String[] packageNames = resolveForegroundPackages(chain.getThisObject());
                        if (PrefsBridge.shouldSkipDoubleTapWakeForPackages(this, packageNames)) {
                            log(Log.DEBUG, Constants.LOG_TAG,
                                    "Rear screen double tap wake skipped for "
                                            + joinPackageNames(packageNames));
                            return true;
                        }
                    }
                    return chain.proceed();
                }
        );

        boolean coverWakeHookInstalled = hookMethodIfPresent(
                coverManagerClass,
                Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                new Class[]{int.class, String.class, int.class},
                Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS + "#"
                        + Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                chain -> {
                    Object groupIdValue = chain.getArgs().get(0);
                    Object detailsValue = chain.getArgs().get(1);
                    if (groupIdValue instanceof Integer
                            && RearScreenWakeMatcher.isRearDoubleTapWake(
                                    (Integer) groupIdValue,
                                    detailsValue)) {
                        String packageName = resolveForegroundPackage(chain.getThisObject());
                        if (PrefsBridge.shouldSkipDoubleTapWakeForPackage(this, packageName)) {
                            log(Log.DEBUG, Constants.LOG_TAG,
                                    "Rear screen double tap wake skipped for " + packageName);
                            return true;
                        }
                    }
                    return chain.proceed();
                }
        );
        return coverHookInstalled && (powerWakeHookInstalled || coverWakeHookInstalled);
    }

    private void installLongPressHooks(@NonNull ClassLoader classLoader) {
        Class<?> gestureClass = findFirstClass(
                classLoader,
                Constants.HOOK_CLASS_LONG_PRESS_OS4,
                Constants.HOOK_CLASS_LONG_PRESS_NEW);
        if (gestureClass != null) {
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_GATE_NEW, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_LONG_PRESS_TOUCH_NEW, new Class[]{MotionEvent.class}, null);
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
            log(Log.INFO, Constants.LOG_TAG,
                    "Long press hook target resolved: " + gestureClass.getName());
        }

        Class<?> legacyGestureClass = findClass(Constants.HOOK_CLASS, classLoader);
        if (legacyGestureClass != null && legacyGestureClass != gestureClass) {
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_GATE, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
        }

        if (gestureClass == null && legacyGestureClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Long press hook targets missing: "
                            + Constants.HOOK_CLASS_LONG_PRESS_OS4 + ", "
                            + Constants.HOOK_CLASS_LONG_PRESS_NEW + ", "
                            + Constants.HOOK_CLASS);
        }
    }

    private void installSwipePanelHook(@NonNull ClassLoader classLoader) {
        Class<?> launcherClass = findClass(Constants.HOOK_CLASS_SUBSCREEN_LAUNCHER, classLoader);
        if (launcherClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "Swipe panel hook target missing: " + Constants.HOOK_CLASS_SUBSCREEN_LAUNCHER);
            return;
        }
        // 在背屏主 Activity 的 dispatchTouchEvent 中做观察者式手势检测：
        // 不替换任何监听器、不消费事件，识别"底部边缘上滑"后把面板挂到本进程窗口上。
        hookMethodIfPresent(
                launcherClass,
                "dispatchTouchEvent",
                new Class[]{MotionEvent.class},
                Constants.HOOK_CLASS_SUBSCREEN_LAUNCHER + "#dispatchTouchEvent",
                chain -> {
                    try {
                        MotionEvent e = (MotionEvent) chain.getArgs().get(0);
                        if (e != null && chain.getThisObject() instanceof Activity) {
                            Activity activity = (Activity) chain.getThisObject();
                            if (SwipePanelHost.isOpeningDrag()) {
                                handleSwipeGesture(activity, e);
                                return true;
                            }
                            if (SwipePanelHost.isShowing()) {
                                // Send events straight to DecorView so our overlay still receives taps and
                                // downward-dismiss gestures, while bypassing the launcher's own gesture manager.
                                return activity.getWindow().superDispatchTouchEvent(e);
                            }
                            if (handleSwipeGesture(activity, e)) return true;
                        }
                    } catch (Throwable ignored) {
                        // 绝不影响原事件分发
                    }
                    return chain.proceed();
                }
        );
        // 面板打开时拦截返回键关闭（背屏系统 Activity 不会把焦点给浮层，View 的 OnKeyListener 收不到返回键）
        hookMethodIfPresent(
                findMethod(launcherClass, "dispatchKeyEvent", new Class[]{KeyEvent.class}),
                Constants.HOOK_CLASS_SUBSCREEN_LAUNCHER + "#dispatchKeyEvent",
                chain -> {
                    try {
                        KeyEvent e = (KeyEvent) chain.getArgs().get(0);
                        if (e != null && e.getAction() == KeyEvent.ACTION_UP
                                && e.getKeyCode() == KeyEvent.KEYCODE_BACK
                                && SwipePanelHost.isShowing()) {
                            SwipePanelHost.dismiss();
                            return true;
                        }
                    } catch (Throwable ignored) {
                        // 不影响原分发
                    }
                    return chain.proceed();
                }
        );

        // 手势排除区必须在触摸开始前设置；等 ACTION_DOWN 才设置已经来不及影响当前手势。
        hookMethodIfPresent(
                findMethod(launcherClass, "onWindowFocusChanged", new Class[]{boolean.class}),
                Constants.HOOK_CLASS_SUBSCREEN_LAUNCHER + "#onWindowFocusChanged",
                chain -> {
                    Object result = chain.proceed();
                    try {
                        Object hasFocus = chain.getArgs().get(0);
                        if (Boolean.TRUE.equals(hasFocus) && chain.getThisObject() instanceof Activity) {
                            Activity activity = (Activity) chain.getThisObject();
                            activity.getWindow().getDecorView().post(() -> ensureGestureExclusion(activity));
                        } else if (chain.getThisObject() instanceof Activity) {
                            resetSwipeState((Activity) chain.getThisObject());
                        }
                    } catch (Throwable ignored) {
                        // 不影响宿主焦点回调
                    }
                    return result;
                }
        );
        log(Log.INFO, Constants.LOG_TAG, "Swipe panel gesture hook installed");
    }

    /**
     * 背屏长按选择壁纸后，宿主只把所选下标保存到自己的 user_pref.json。
     * 系统设置页不读这个下标，而是直接预览 Secure Settings 中 theme_rear_widget
     * 的第一项，因此两边会长期显示不同壁纸。
     */
    private void installRearScreenSelectionSyncHook(@NonNull ClassLoader classLoader) {
        Class<?> mainPanelClass = findClass(Constants.HOOK_CLASS_MAIN_PANEL, classLoader);
        if (mainPanelClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Rear selection sync target missing: " + Constants.HOOK_CLASS_MAIN_PANEL);
            return;
        }

        hookMethodIfPresent(
                mainPanelClass,
                Constants.HOOK_METHOD_REQUEST_EXIT_EDIT,
                new Class[]{boolean.class},
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_REQUEST_EXIT_EDIT,
                chain -> {
                    View panel = chain.getThisObject() instanceof View
                            ? (View) chain.getThisObject()
                            : null;
                    Object cancelValue = chain.getArgs().isEmpty()
                            ? null
                            : chain.getArgs().get(0);
                    boolean cancel = Boolean.TRUE.equals(cancelValue);

                    if (panel == null || cancel || !PrefsBridge.shouldFixRearScreenApply(this)) {
                        if (panel != null) pendingRearSelections.remove(panel);
                        return chain.proceed();
                    }

                    PendingRearSelection pending = capturePendingRearSelection(panel);
                    if (pending == null) {
                        pendingRearSelections.remove(panel);
                    } else {
                        pendingRearSelections.put(panel, pending);
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear selection commit requested: id=" + pending.wallpaperId
                                        + ", index=" + pending.selectedIndex);
                    }
                    return chain.proceed();
                }
        );

        hookMethodIfPresent(
                mainPanelClass,
                Constants.HOOK_METHOD_SAVE_USER_SELECTION,
                new Class[]{},
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_SAVE_USER_SELECTION,
                chain -> {
                    Object result = chain.proceed();
                    if (PrefsBridge.shouldFixRearScreenApply(this)
                            && chain.getThisObject() instanceof View panel) {
                        consumePendingRearSelection(panel);
                    } else if (chain.getThisObject() instanceof View panel) {
                        pendingRearSelections.remove(panel);
                    }
                    return result;
                }
        );
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
                SystemClock.elapsedRealtime());
    }

    private void consumePendingRearSelection(@NonNull View panel) {
        PendingRearSelection pending = pendingRearSelections.get(panel);
        if (pending == null) return;

        long age = SystemClock.elapsedRealtime() - pending.requestedAtElapsed;
        if (age < 0L || age > REAR_SELECTION_PENDING_TIMEOUT_MS) {
            pendingRearSelections.remove(panel);
            log(Log.WARN, Constants.LOG_TAG,
                    "Ignored expired rear selection commit: id=" + pending.wallpaperId
                            + ", ageMs=" + age);
            return;
        }

        Integer committedId = resolveWallpaperId(panel, false);
        if (committedId == null || committedId.intValue() != pending.wallpaperId) {
            pendingRearSelections.remove(panel);
            log(Log.WARN, Constants.LOG_TAG,
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
                log(Log.DEBUG, Constants.LOG_TAG,
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
                log(Log.INFO, Constants.LOG_TAG,
                        "Synced rear selection to Settings: id=" + selectedId
                                + ", index=" + selectedIndex);
            } else {
                log(Log.WARN, Constants.LOG_TAG,
                        "Failed to write rear selection to Settings: id=" + selectedId);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "syncSelectedWallpaperToSettings failed", e);
        }
    }

    private static final class PendingRearSelection {
        final int wallpaperId;
        final int selectedIndex;
        final long requestedAtElapsed;

        PendingRearSelection(int wallpaperId, int selectedIndex, long requestedAtElapsed) {
            this.wallpaperId = wallpaperId;
            this.selectedIndex = selectedIndex;
            this.requestedAtElapsed = requestedAtElapsed;
        }
    }

    /**
     * 识别并接管"底部边缘上滑"：超过阈值后，面板顶部持续跟随手指；松手时再根据
     * 拖动距离和末端速度决定展开或退回。每个 Activity 维护独立手势状态。
     */
    private boolean handleSwipeGesture(@NonNull Activity activity, @NonNull MotionEvent e) {
        SwipeState st = swipeStates.get(activity);
        if (st == null) {
            st = new SwipeState();
            swipeStates.put(activity, st);
        }
        boolean wasDragging = st.draggingPanel;
        if (!activity.hasWindowFocus()
                || isHostPanelShowing(activity)
                || !PrefsBridge.shouldEnableSwipePanel(this)
                || e.getPointerCount() != 1) {
            if (wasDragging) SwipePanelHost.finishOpeningDrag(false, 0f);
            st.reset();
            return wasDragging;
        }
        float threshold = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                Constants.GESTURE_SWIPE_UP_DP,
                activity.getResources().getDisplayMetrics());
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            st.downTime = e.getDownTime();
            st.startX = e.getRawX();
            st.startY = e.getRawY();
            st.lastY = st.startY;
            st.lastEventTime = e.getEventTime();
            st.velocityY = 0f;
            int decorHeight = activity.getWindow().getDecorView().getHeight();
            st.armed = decorHeight > 0
                    && e.getRawY() > decorHeight * Constants.GESTURE_BOTTOM_EDGE_RATIO;
            st.draggingPanel = false;
            return false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!st.armed || st.downTime != e.getDownTime()) {
                if (st.draggingPanel) SwipePanelHost.finishOpeningDrag(false, 0f);
                st.reset();
                return wasDragging;
            }
            if (st.draggingPanel) {
                updateSwipeVelocity(st, e);
                SwipePanelHost.updateOpeningDrag(e.getRawY());
                return true;
            }
            if (st.armed) {
                float dy = st.startY - e.getRawY();
                float dx = Math.abs(e.getRawX() - st.startX);
                if (dy > threshold && dy > dx * 1.15f) {
                    long now = SystemClock.uptimeMillis();
                    if (now - st.lastTrigger > Constants.GESTURE_TRIGGER_COOLDOWN_MS) {
                        st.lastTrigger = now;
                        st.draggingPanel = true;
                        updateSwipeVelocity(st, e);
                        SwipePanelHost.beginOpeningDrag(activity, e.getRawY());
                        cancelHostTouchTarget(activity, e);
                        Log.d(Constants.LOG_TAG, "Swipe-up drag started");
                        return true;
                    }
                }
            }
            return false;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (st.draggingPanel) {
                updateSwipeVelocity(st, e);
                SwipePanelHost.updateOpeningDrag(e.getRawY());
                int height = activity.getWindow().getDecorView().getHeight();
                float distance = st.startY - e.getRawY();
                float minDistance = Math.max(threshold * 2f, height * 0.22f);
                float minFlingVelocity = Math.max(
                        threshold * 6f,
                        ViewConfiguration.get(activity).getScaledMinimumFlingVelocity() * 4f);
                boolean open = distance >= minDistance
                        || (action == MotionEvent.ACTION_UP && st.velocityY <= -minFlingVelocity);
                float velocityY = st.velocityY;
                SwipePanelHost.finishOpeningDrag(open, velocityY);
                Log.d(Constants.LOG_TAG, "Swipe-up drag finished: open=" + open
                        + ", distance=" + distance + ", velocityY=" + velocityY);
                String diagnostic = "swipe drag finished: open=" + open
                        + ", distance=" + Math.round(distance)
                        + ", velocityY=" + Math.round(velocityY);
                activity.getWindow().getDecorView().post(
                        () -> DiagnosticLogStore.recordRemote(activity, diagnostic));
                st.reset();
                return true;
            }
            st.reset();
            return false;
        }
        return wasDragging;
    }

    private static void updateSwipeVelocity(@NonNull SwipeState st, @NonNull MotionEvent e) {
        long dt = e.getEventTime() - st.lastEventTime;
        if (dt > 0L) {
            float instant = (e.getRawY() - st.lastY) * 1000f / dt;
            st.velocityY = st.velocityY == 0f ? instant : st.velocityY * 0.35f + instant * 0.65f;
        }
        st.lastY = e.getRawY();
        st.lastEventTime = e.getEventTime();
    }

    private static void cancelHostTouchTarget(@NonNull Activity activity, @NonNull MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            activity.getWindow().superDispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    private void resetSwipeState(@NonNull Activity activity) {
        SwipeState state = swipeStates.get(activity);
        if (state != null) state.reset();
    }

    /** Do not open our panel while Xiaomi's notification/service-assistant panel is visible. */
    @SuppressLint("DiscouragedApi")
    private boolean isHostPanelShowing(@NonNull Activity activity) {
        try {
            int notificationId = activity.getResources().getIdentifier(
                    "notification_panel", "id", Constants.TARGET_PACKAGE);
            View notificationPanel = notificationId != 0 ? activity.findViewById(notificationId) : null;
            if (notificationPanel != null
                    && notificationPanel.getVisibility() == View.VISIBLE
                    && notificationPanel.getAlpha() > 0.01f) {
                return true;
            }

            int assistantId = activity.getResources().getIdentifier(
                    "smart_assistant_panel", "id", Constants.TARGET_PACKAGE);
            View assistantPanel = assistantId != 0 ? activity.findViewById(assistantId) : null;
            if (assistantPanel != null
                    && assistantPanel.getVisibility() == View.VISIBLE
                    && assistantPanel.getTranslationY() > -activity.getWindow().getDecorView().getHeight() + 1) {
                return true;
            }
        } catch (Throwable ignored) {
            // A future host version may rename these views; session validation still prevents stale events.
        }
        return false;
    }

    /**
     * 把背屏底部边缘区域从系统手势中排除，避免上滑被系统导航抢走
     * （系统导航响应该手势时副屏会闪一下黑，与我们的面板撞车）。仅设置一次。
     */
    private void ensureGestureExclusion(@NonNull Activity activity) {
        if (exclusionApplied.contains(activity)) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
            int top = Math.max(0, (int) (decor.getHeight() * Constants.GESTURE_BOTTOM_EDGE_RATIO));
            Rect rect = new Rect(0, top, decor.getWidth(), decor.getHeight());
            decor.setSystemGestureExclusionRects(Collections.singletonList(rect));
            exclusionApplied.add(activity);
            log(Log.DEBUG, Constants.LOG_TAG, "Bottom-edge gesture exclusion set");
        } catch (Throwable ignored) {
            // 部分 ROM 不支持，忽略
        }
    }

    private static Intent moduleSettingsIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(
                Constants.MODULE_PACKAGE,
                Constants.MODULE_PACKAGE + ".ui.MainActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static final class SwipeState {
        float startX;
        float startY;
        float lastY;
        float velocityY;
        long downTime;
        long lastEventTime;
        boolean armed;
        boolean draggingPanel;
        long lastTrigger;

        void reset() {
            startX = 0f;
            startY = 0f;
            lastY = 0f;
            velocityY = 0f;
            downTime = 0L;
            lastEventTime = 0L;
            armed = false;
            draggingPanel = false;
        }
    }

    private void installWallpaperLimitHook(@NonNull ClassLoader classLoader) {
        Class<?> viewModelClass = findClass(Constants.THEME_REAR_VIEWMODEL_CLASS, classLoader);
        if (viewModelClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "Theme hook target missing: " + Constants.THEME_REAR_VIEWMODEL_CLASS);
            return;
        }
        Method applyCheckMethod = findFirstDeclaredMethod(
                viewModelClass,
                new Class[]{List.class},
                Constants.THEME_APPLY_CHECK_METHOD_OS4,
                Constants.THEME_APPLY_CHECK_METHOD);
        if (applyCheckMethod == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Hook targets missing: " + Constants.THEME_REAR_VIEWMODEL_CLASS
                            + "#[" + Constants.THEME_APPLY_CHECK_METHOD_OS4
                            + ", " + Constants.THEME_APPLY_CHECK_METHOD + "]");
            return;
        }
        hookMethodIfPresent(
                applyCheckMethod,
                Constants.THEME_REAR_VIEWMODEL_CLASS + "#" + applyCheckMethod.getName(),
                chain -> {
                    if (PrefsBridge.shouldRemoveWallpaperLimit(this)) {
                        return true;
                    }
                    return chain.proceed();
                }
        );
    }

    private void installRearScreenApplyFixHook(@NonNull ClassLoader classLoader) {
        Class<?> applyResultClass = findClass(Constants.THEME_APPLY_RESULT_CLASS, classLoader);
        if (applyResultClass == null) {
            return;
        }

        hookMethodIfPresent(
                applyResultClass,
                Constants.THEME_APPLY_RESULT_METHOD,
                new Class[]{Object.class},
                Constants.THEME_APPLY_RESULT_CLASS + "#" + Constants.THEME_APPLY_RESULT_METHOD,
                chain -> {
                    boolean fixApply = PrefsBridge.shouldFixRearScreenApply(this);
                    boolean removeLimit = PrefsBridge.shouldRemoveWallpaperLimit(this);
                    Object bean = fixApply || removeLimit
                            ? getFieldValue(chain.getThisObject(), Constants.THEME_APPLY_BEAN_FIELD)
                            : null;
                    ThemeMagicRepairTarget repairTarget = ThemeMagicRepairTarget.from(bean);
                    if (bean != null) {
                        if (fixApply) {
                            promoteReappliedWallpaper(bean, classLoader);
                            fillSnapshotPaths(bean);
                            patchRightsPath(bean, classLoader);
                            patchMtzPath(bean);
                        }
                        patchThemeMagicAssetAccess(bean);
                    }
                    Object result = chain.proceed();
                    if (repairTarget != null) {
                        scheduleThemeMagicAssetRepair(repairTarget);
                    }
                    return result;
                }
        );
    }

    private void installThemeSettingsShortcutHook(@NonNull ClassLoader classLoader) {
        Class<?> entryConfigCompanionClass = findClass(
                Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS, classLoader);
        if (entryConfigCompanionClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings entry config target missing: "
                            + Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS);
            return;
        }

        hookMethodIfPresent(
                entryConfigCompanionClass,
                "k",
                new Class[]{Context.class},
                Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS + "#k",
                chain -> {
                    Object result = chain.proceed();
                    if (!PrefsBridge.shouldShowThemeSettingsShortcut(this)) {
                        return result;
                    }
                    Object context = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (context instanceof Context) {
                        return injectThemeSettingsShortcutList(
                                result, (Context) context, classLoader);
                    }
                    return result;
                }
        );

        Class<?> adapterClass = findClass(Constants.THEME_REAR_SETTING_ADAPTER_CLASS, classLoader);
        Class<?> viewHolderClass = findClass(
                "androidx.recyclerview.widget.RecyclerView$ViewHolder", classLoader);
        if (adapterClass == null || viewHolderClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings adapter target missing");
            return;
        }
        hookMethodIfPresent(
                findDeclaredMethod(adapterClass, "onBindViewHolder",
                        new Class[]{viewHolderClass, int.class}),
                Constants.THEME_REAR_SETTING_ADAPTER_CLASS + "#onBindViewHolder",
                chain -> {
                    Object result = chain.proceed();
                    List<?> data = getThemeSettingsAdapterData(chain.getThisObject());
                    Object positionValue = chain.getArgs().size() > 1
                            ? chain.getArgs().get(1)
                            : null;
                    int position = positionValue instanceof Number
                            ? ((Number) positionValue).intValue()
                            : -1;
                    if (data != null && position >= 0 && position < data.size()
                            && themeSettingsShortcutControllers.contains(data.get(position))) {
                        Object holder = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        bindThemeSettingsShortcutRow(holder);
                    }
                    return result;
                }
        );
    }

    private Object injectThemeSettingsShortcutList(
            @Nullable Object result,
            @NonNull Context context,
            @NonNull ClassLoader classLoader
    ) {
        if (!(result instanceof List<?> rawList)) return result;
        for (Object item : rawList) {
            if (themeSettingsShortcutControllers.contains(item)) return result;
        }

        List<String> keys = new ArrayList<>();
        for (Object item : rawList) {
            Object key = callNoArgMethodQuietly(item, "g");
            if (key instanceof String) keys.add((String) key);
        }
        int insertionIndex = SettingsEntryPlacement.insertionIndexAfterAnchor(keys);
        if (insertionIndex < 0) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings shortcut anchor missing in rear screen page");
            return result;
        }

        Object shortcut = createThemeSettingsShortcutController(context, classLoader);
        if (shortcut == null) return result;

        ArrayList<Object> copy = new ArrayList<>(rawList);
        copy.add(Math.min(insertionIndex, copy.size()), shortcut);
        themeSettingsShortcutControllers.add(shortcut);
        log(Log.INFO, Constants.LOG_TAG,
                "MiBackscreen theme settings entry inserted after "
                        + keys.get(insertionIndex - 1));
        return copy;
    }

    @Nullable
    private Object createThemeSettingsShortcutController(
            @NonNull Context context,
            @NonNull ClassLoader classLoader
    ) {
        try {
            Class<?> controllerClass = findClass(
                    Constants.THEME_USER_GUIDE_CONTROLLER_CLASS, classLoader);
            if (controllerClass == null) return null;

            Constructor<?> constructor = controllerClass.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            Object controller = constructor.newInstance(context);
            setFieldValue(controller, Constants.THEME_BASE_CONTROLLER_TITLE_FIELD, "MiBackscreen");
            setFieldValue(controller, Constants.THEME_USER_GUIDE_INTENT_FIELD, moduleSettingsIntent());
            return controller;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "createThemeSettingsShortcutController failed", e);
            return null;
        }
    }

    @Nullable
    private List<?> getThemeSettingsAdapterData(@Nullable Object adapter) {
        Object data = getFieldValue(adapter, Constants.THEME_REAR_SETTING_ADAPTER_DATA_FIELD);
        if (isThemeSettingsControllerList(data)) return (List<?>) data;

        if (adapter == null) return null;
        Class<?> owner = adapter.getClass();
        while (owner != null) {
            for (Field field : owner.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (isThemeSettingsControllerList(value)) {
                        return (List<?>) value;
                    }
                } catch (Throwable ignored) {
                    // Try the next field.
                }
            }
            owner = owner.getSuperclass();
        }
        return null;
    }

    private boolean isThemeSettingsControllerList(@Nullable Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return false;
        boolean hasKnownRearScreenEntry = false;
        for (Object item : list) {
            if (themeSettingsShortcutControllers.contains(item)) return true;
            Object key = callNoArgMethodQuietly(item, "g");
            if ("user_guide".equals(key) || "serve_assistant".equals(key)) {
                hasKnownRearScreenEntry = true;
            }
        }
        return hasKnownRearScreenEntry;
    }

    @SuppressLint("DiscouragedApi")
    private void bindThemeSettingsShortcutRow(@Nullable Object holder) {
        Object itemViewValue = getFieldValue(holder, "itemView");
        if (!(itemViewValue instanceof View row)) return;
        Context context = row.getContext();
        int titleId = context.getResources().getIdentifier(
                "title", "id", Constants.THEME_STORE_PACKAGE);
        int iconId = context.getResources().getIdentifier(
                "icon", "id", Constants.THEME_STORE_PACKAGE);
        if (titleId != 0) {
            View titleView = row.findViewById(titleId);
            if (titleView instanceof TextView) {
                ((TextView) titleView).setText("MiBackscreen");
            }
        }
        if (iconId != 0) {
            View iconView = row.findViewById(iconId);
            if (iconView instanceof ImageView) {
                try {
                    Drawable icon = context.getPackageManager()
                            .getApplicationIcon(Constants.MODULE_PACKAGE);
                    ((ImageView) iconView).setImageDrawable(icon);
                } catch (Throwable ignored) {
                    // Title and click behavior are still useful if icon lookup fails.
                }
            }
        }
        row.setOnClickListener(v -> {
            try {
                v.getContext().startActivity(moduleSettingsIntent());
            } catch (Throwable e) {
                log(Log.WARN, Constants.LOG_TAG,
                        "MiBackscreen theme settings entry launch failed", e);
            }
        });
    }

    /**
     * 设置页的顶部预览来自主题商店数据库（按 position 降序），并不直接采用
     * theme_rear_widget 的 changed 标记。背屏中心完成切换后已经把当前 widget id
     * 同步到 Secure Settings；设置页冷启动前再据此提升数据库中的对应项。
     */
    private void installThemeSettingsSelectionSyncHook(@NonNull ClassLoader classLoader) {
        Class<?> activityClass = findClass(Constants.THEME_REAR_SETTING_ACTIVITY, classLoader);
        if (activityClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings sync target missing: " + Constants.THEME_REAR_SETTING_ACTIVITY);
            return;
        }

        hookMethodIfPresent(
                activityClass,
                "onCreate",
                new Class[]{Bundle.class},
                Constants.THEME_REAR_SETTING_ACTIVITY + "#onCreate",
                chain -> {
                    if (PrefsBridge.shouldFixRearScreenApply(this)
                            && chain.getThisObject() instanceof Activity activity) {
                        runThemeDatabaseSelectionSync(activity, classLoader);
                    }
                    return chain.proceed();
                }
        );

        hookMethodIfPresent(
                activityClass,
                "onResume",
                new Class[]{},
                Constants.THEME_REAR_SETTING_ACTIVITY + "#onResume",
                chain -> {
                    if (PrefsBridge.shouldFixRearScreenApply(this)
                            && chain.getThisObject() instanceof Activity activity) {
                        runThemeDatabaseSelectionSync(activity, classLoader);
                    }
                    return chain.proceed();
                }
        );
    }

    private void runThemeDatabaseSelectionSync(
            @NonNull Activity activity,
            @NonNull ClassLoader classLoader
    ) {
        Integer selectedId = readSelectedRearWidgetId(activity);
        long now = SystemClock.elapsedRealtime();
        if (selectedId == null
                || (selectedId.equals(lastThemeDatabaseSyncedWidgetId)
                && now - lastThemeDatabaseSyncedAtElapsed < THEME_DATABASE_SYNC_DEDUP_WINDOW_MS)) {
            return;
        }

        // Room 禁止主线程数据库访问。先在短任务中完成排序落库，再让页面创建或恢复，
        // 尽量让 LiveData 第一次（或恢复后）渲染拿到当前背屏壁纸；慢路径转入后台，
        // 避免主题商店页面被模块同步阻塞数秒。
        Thread syncThread = new Thread(
                () -> {
                    if (syncThemeDatabaseToRearSelection(activity, classLoader, selectedId)) {
                        lastThemeDatabaseSyncedWidgetId = selectedId;
                        lastThemeDatabaseSyncedAtElapsed = SystemClock.elapsedRealtime();
                    }
                },
                "MiBackscreen-RearSelectionSync");
        syncThread.setDaemon(true);
        syncThread.start();
        try {
            syncThread.join(500L);
            if (syncThread.isAlive()) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Theme settings selection sync continuing in background");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings selection sync interrupted", e);
        }
    }

    @Nullable
    private Integer readSelectedRearWidgetId(@NonNull Activity activity) {
        try {
            String raw = Settings.Secure.getString(
                    activity.getContentResolver(), Constants.SECURE_THEME_REAR_WIDGET);
            if (isEmpty(raw)) return null;
            JSONArray data = new JSONObject(raw).optJSONArray("data");
            JSONObject selectedWidget = data != null ? data.optJSONObject(0) : null;
            return selectedWidget != null && selectedWidget.has("id")
                    ? selectedWidget.getInt("id")
                    : null;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "readSelectedRearWidgetId failed", e);
            return null;
        }
    }

    private boolean syncThemeDatabaseToRearSelection(
            @NonNull Activity activity,
            @NonNull ClassLoader classLoader,
            int selectedId
    ) {
        try {
            Class<?> managerClass = findClass(Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) return false;
            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) return false;
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            Object manager = companion != null
                    ? callMethod(companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD)
                    : null;
            if (manager == null) return false;
            Object listValue = callMethod(
                    manager, Constants.THEME_REAR_DATA_MANAGER_GET_LIST_METHOD);
            if (!(listValue instanceof List<?> items) || items.isEmpty()) return false;

            Object selectedItem = null;
            int selectedPosition = Integer.MIN_VALUE;
            int maxPosition = Integer.MIN_VALUE;
            for (Object item : items) {
                if (item == null) continue;
                Object positionValue = callMethod(item, "getPosition");
                if (!(positionValue instanceof Number)) continue;
                int position = ((Number) positionValue).intValue();
                maxPosition = Math.max(maxPosition, position);

                String resId = (String) callMethod(item, "getResId");
                String applyId = (String) callMethod(item, "getApplyId");
                int widgetId = (String.valueOf(resId) + String.valueOf(applyId)).hashCode();
                if (widgetId == selectedId) {
                    selectedItem = item;
                    selectedPosition = position;
                }
            }

            if (selectedItem == null) return false;
            if (selectedPosition >= maxPosition) return true;
            if (maxPosition == Integer.MAX_VALUE) return false;
            int promotedPosition = maxPosition + 1;
            callMethod(selectedItem, "setPosition", promotedPosition);
            callMethod(manager, Constants.THEME_REAR_DATA_MANAGER_UPSERT_METHOD, selectedItem);
            log(Log.INFO, Constants.LOG_TAG,
                    "Synced Theme DB to rear selection: id=" + selectedId
                            + ", position=" + selectedPosition + "->" + promotedPosition);
            return true;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "syncThemeDatabaseToRearSelection failed", e);
            return false;
        }
    }

    /**
     * 主题商店重新应用已经存在于“我的背屏”中的壁纸时，只会更新 changed 标记，
     * 不会把该项的 position 移到列表首位。背屏服务能识别 changed，但系统设置页直接
     * 预览 position 最大的第一项，因此会一直显示之前的壁纸。
     *
     * 在宿主原流程写数据库、runtime.json 和 theme_rear_widget 之前提升当前项，保证
     * 三份状态使用同一顺序。只处理已经存在且当前不在首位的 resId + applyId。
     */
    private void promoteReappliedWallpaper(Object bean, ClassLoader classLoader) {
        try {
            Class<?> managerClass = findClass(Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Theme hook target missing: " + Constants.THEME_REAR_DATA_MANAGER_CLASS);
                return;
            }

            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) {
                throw new NoSuchFieldException(
                        Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            }
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            if (companion == null) return;

            Object manager = callMethod(
                    companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD);
            if (manager == null) return;

            Object value = callMethod(
                    manager, Constants.THEME_REAR_DATA_MANAGER_GET_LIST_METHOD);
            if (!(value instanceof List<?> items) || items.isEmpty()) return;

            String targetResId = (String) callMethod(bean, "getResId");
            String targetApplyId = (String) callMethod(bean, "getApplyId");
            int maxPosition = Integer.MIN_VALUE;
            int currentPosition = Integer.MIN_VALUE;
            boolean existingItem = false;

            for (Object item : items) {
                if (item == null) continue;
                Object positionValue = callMethod(item, "getPosition");
                if (!(positionValue instanceof Number)) continue;
                int position = ((Number) positionValue).intValue();
                maxPosition = Math.max(maxPosition, position);

                String resId = (String) callMethod(item, "getResId");
                String applyId = (String) callMethod(item, "getApplyId");
                if (java.util.Objects.equals(targetResId, resId)
                        && java.util.Objects.equals(targetApplyId, applyId)) {
                    existingItem = true;
                    currentPosition = position;
                }
            }

            if (!existingItem || currentPosition >= maxPosition) return;
            if (maxPosition == Integer.MAX_VALUE) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Current rear wallpaper not promoted: position overflow");
                return;
            }

            int promotedPosition = maxPosition + 1;
            callMethod(bean, "setPosition", promotedPosition);
            log(Log.INFO, Constants.LOG_TAG,
                    "Promoted current rear wallpaper: resId=" + targetResId
                            + ", position=" + currentPosition + "->" + promotedPosition);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "promoteReappliedWallpaper failed", e);
        }
    }

    @Nullable
    private static Field findStaticCompanionField(Class<?> owner, String preferredName) {
        try {
            return owner.getDeclaredField(preferredName);
        } catch (NoSuchFieldException ignored) {
            String companionClassName = owner.getName() + "$Companion";
            for (Field field : owner.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && companionClassName.equals(field.getType().getName())) {
                    return field;
                }
            }
            return null;
        }
    }

    private void fillSnapshotPaths(Object bean) {
        try {
            String localPath = (String) callMethod(bean, "getResLocalPath");
            String snapshotPath = (String) callMethod(bean, "getResSnapshotPath");
            if (isEmpty(snapshotPath) && !isEmpty(localPath) && new File(localPath).exists()) {
                callMethod(bean, "setResSnapshotPath", localPath);
            }
            String metaSrc = (String) callMethod(bean, "getMetaPath");
            String metaSnap = (String) callMethod(bean, "getMetaSnapshotPath");
            if (isEmpty(metaSnap) && !isEmpty(metaSrc) && new File(metaSrc).exists()) {
                callMethod(bean, "setMetaSnapshotPath", metaSrc);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "fillSnapshotPaths failed", e);
        }
    }

    private void patchRightsPath(Object bean, ClassLoader classLoader) {
        try {
            String rightPath = (String) callMethod(bean, "getRightPath");
            if (isEmpty(rightPath) || !rightPath.endsWith(".mra")) {
                return;
            }
            String localPath = (String) callMethod(bean, "getResLocalPath");
            if (isEmpty(localPath) || !localPath.contains("/rearscreen/")) {
                return;
            }

            String rightsDir = resolveRightsDir(classLoader);
            String baseName = new File(rightPath).getName();
            if (!baseName.startsWith("rearscreen_")) {
                baseName = "rearscreen_" + baseName;
            }
            String target = rightsDir + baseName;

            File targetFile = new File(target);
            if (targetFile.exists()) {
                grantReadAccess(targetFile);
                callMethod(bean, "setRightPath", target);
                return;
            }

            File sourceFile = new File(rightPath);
            if (!sourceFile.exists()) {
                File fallback = findNewestRightsFile(rightsDir, target);
                if (fallback != null) {
                    safeCopy(fallback, targetFile);
                    grantReadAccess(targetFile);
                    callMethod(bean, "setRightPath", target);
                } else {
                    callMethod(bean, "setRightPath", target);
                }
                return;
            }

            if (!targetFile.exists()) {
                safeCopy(sourceFile, targetFile);
                grantReadAccess(targetFile);
            }
            callMethod(bean, "setRightPath", target);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "patchRightsPath failed", e);
        }
    }

    private void patchMtzPath(Object bean) {
        try {
            String srcMtz = (String) callMethod(bean, "getResLocalPath");
            if (isEmpty(srcMtz)) {
                return;
            }
            File srcFile = new File(srcMtz);
            if (!srcFile.exists() || srcFile.isDirectory() || !srcMtz.endsWith(".mrc")) {
                return;
            }
            if (!srcMtz.startsWith("/product/") && !srcMtz.startsWith("/system/") && !srcMtz.startsWith("/vendor/")) {
                return;
            }
            String destMtz = (String) callMethod(bean, "getRuntimeDirWithAuth");
            if (isEmpty(destMtz)) {
                return;
            }
            File destFile = new File(destMtz);
            if (!destFile.exists()) {
                safeCopy(srcFile, destFile);
                grantReadAccess(destFile);
            }
            callMethod(bean, "setResLocalPath", destMtz);
            callMethod(bean, "setResSnapshotPath", destMtz);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "patchMtzPath failed", e);
        }
    }

    private void patchThemeMagicAssetAccess(Object bean) {
        try {
            LinkedHashSet<File> files = new LinkedHashSet<>();
            String editConfigPath = asString(callNoArgMethodQuietly(bean, "getMamlEditConfigPath"));
            addThemeMagicFile(files, editConfigPath);
            addThemeMagicFile(files, asString(callNoArgMethodQuietly(bean, "getSnapshotPreviewPath")));
            addThemeMagicFile(files, asString(callNoArgMethodQuietly(bean, "getMetaPath")));
            addThemeMagicFile(files, asString(callNoArgMethodQuietly(bean, "getMetaSnapshotPath")));

            if (!isEmpty(editConfigPath)) {
                collectThemeMagicFilesFromEditConfig(new File(editConfigPath), files);
            }

            int existingFiles = 0;
            int missingFiles = 0;
            for (File file : files) {
                if (file.exists()) {
                    existingFiles++;
                } else {
                    missingFiles++;
                }
                grantThemeMagicAssetAccess(file);
            }
            if (!files.isEmpty()) {
                log(Log.INFO, Constants.LOG_TAG,
                        "Patched rear screen theme_magic assets: total=" + files.size()
                                + ", existing=" + existingFiles
                                + ", missing=" + missingFiles);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "patchThemeMagicAssetAccess failed", e);
        }
    }

    private void scheduleThemeMagicAssetRepair(@NonNull ThemeMagicRepairTarget target) {
        Thread repairThread = new Thread(
                () -> {
                    long[] delays = {0L, 300L, 1000L, 2500L};
                    for (long delay : delays) {
                        if (delay > 0L) {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        repairThemeMagicAssets(target);
                    }
                },
                "MiBackscreen-ThemeMagicAssetRepair");
        repairThread.setDaemon(true);
        repairThread.start();
    }

    private void repairThemeMagicAssets(@NonNull ThemeMagicRepairTarget target) {
        if (isEmpty(target.editConfigPath)) {
            return;
        }
        File editConfig = new File(target.editConfigPath);
        if (!editConfig.exists() || !editConfig.isFile()) {
            return;
        }
        long length = editConfig.length();
        if (length < 0 || length > MAX_EDIT_CONFIG_BYTES) {
            return;
        }
        try {
            String raw = readUtf8(editConfig).trim();
            if (raw.isEmpty()) {
                return;
            }
            Object root = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            RepairStats stats = new RepairStats();
            boolean changed = rewriteThemeMagicFileReferences(root, target, stats);
            if (changed) {
                byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(editConfig, false)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                grantThemeMagicAssetAccess(editConfig);
                log(Log.INFO, Constants.LOG_TAG,
                        "Rewrote rear screen theme_magic asset references: copied="
                                + stats.copied + ", missing=" + stats.missing);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "repairThemeMagicAssets failed", e);
        }
    }

    private boolean rewriteThemeMagicFileReferences(
            @Nullable Object node,
            @NonNull ThemeMagicRepairTarget target,
            @NonNull RepairStats stats
    ) throws Throwable {
        boolean changed = false;
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (value instanceof String) {
                    String rewritten = rewriteThemeMagicFileReference((String) value, target, stats);
                    if (!((String) value).equals(rewritten)) {
                        object.put(key, rewritten);
                        changed = true;
                    }
                } else if (rewriteThemeMagicFileReferences(value, target, stats)) {
                    changed = true;
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    String rewritten = rewriteThemeMagicFileReference((String) value, target, stats);
                    if (!((String) value).equals(rewritten)) {
                        array.put(i, rewritten);
                        changed = true;
                    }
                } else if (rewriteThemeMagicFileReferences(value, target, stats)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    @NonNull
    private String rewriteThemeMagicFileReference(
            @NonNull String path,
            @NonNull ThemeMagicRepairTarget target,
            @NonNull RepairStats stats
    ) {
        if (!isThemeMagicUserPath(path)) {
            return path;
        }
        File source = new File(path);
        if (!source.exists() || !source.isFile()) {
            stats.missing++;
            return path;
        }
        File destination = resolveRuntimeAssetPath(source, target);
        if (!destination.exists() || destination.length() != source.length()) {
            if (!safeCopy(source, destination)) {
                return path;
            }
        }
        grantReadAccess(destination);
        stats.copied++;
        return destination.getAbsolutePath();
    }

    @NonNull
    private File resolveRuntimeAssetPath(
            @NonNull File source,
            @NonNull ThemeMagicRepairTarget target
    ) {
        String resId = sanitizeFilePart(target.resId, "unknown");
        String applyId = sanitizeFilePart(target.applyId, "current");
        String name = sanitizeFilePart(source.getName(), "asset");
        return new File(
                "/data/system/theme/rearScreen",
                "mibackscreen_" + resId + "_" + applyId + "_" + name);
    }

    private void collectThemeMagicFilesFromEditConfig(@NonNull File editConfig, @NonNull Set<File> files) {
        if (!editConfig.exists() || !editConfig.isFile()) {
            return;
        }
        long length = editConfig.length();
        if (length < 0 || length > MAX_EDIT_CONFIG_BYTES) {
            return;
        }
        try {
            String raw = readUtf8(editConfig).trim();
            if (raw.isEmpty()) {
                return;
            }
            Object root = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            collectThemeMagicFilesFromJson(root, files);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Failed to parse rear screen editConfig: " + editConfig.getAbsolutePath(), e);
        }
    }

    private void collectThemeMagicFilesFromJson(@Nullable Object node, @NonNull Set<File> files) {
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object value = object.opt(keys.next());
                if (value instanceof String) {
                    addThemeMagicFile(files, (String) value);
                } else {
                    collectThemeMagicFilesFromJson(value, files);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    addThemeMagicFile(files, (String) value);
                } else {
                    collectThemeMagicFilesFromJson(value, files);
                }
            }
        }
    }

    private void addThemeMagicFile(@NonNull Set<File> files, @Nullable String path) {
        if (isEmpty(path) || !isThemeMagicUserPath(path)) {
            return;
        }
        files.add(new File(path));
    }

    private void grantThemeMagicAssetAccess(@NonNull File file) {
        grantThemeMagicDirectoryAccess(file.getParentFile());
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            grantThemeMagicDirectoryAccess(file);
        } else {
            grantReadAccess(file);
        }
    }

    @SuppressLint("SetWorldReadable")
    private void grantThemeMagicDirectoryAccess(@Nullable File dir) {
        File current = dir;
        while (current != null && isThemeMagicPath(current.getAbsolutePath())) {
            current.setReadable(true, false);
            current.setWritable(true, true);
            current.setExecutable(true, false);
            current = current.getParentFile();
        }
    }

    private String resolveRightsDir(ClassLoader classLoader) {
        String[] fieldNames = {
                Constants.THEME_RIGHTS_DIR_FIELD_OS4,
                Constants.THEME_RIGHTS_DIR_FIELD_PRIMARY,
                Constants.THEME_RIGHTS_DIR_FIELD_FALLBACK
        };
        for (String fieldName : fieldNames) {
            String dir = getStaticStringField(
                    classLoader, Constants.THEME_RESOURCE_CONSTANTS_CLASS, fieldName);
            if (isValidRightsDir(dir)) {
                return dir.endsWith("/") ? dir : dir + "/";
            }
            if (!isEmpty(dir)) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Ignored invalid theme rights dir from " + fieldName + ": " + dir);
            }
        }
        return Constants.THEME_RIGHTS_DIR_DEFAULT;
    }

    private static boolean isValidRightsDir(@Nullable String dir) {
        if (isEmpty(dir) || !dir.startsWith("/")) return false;
        String normalized = dir.endsWith("/") ? dir : dir + "/";
        return normalized.contains("/theme/rights/");
    }

    private File findNewestRightsFile(String dirPath, String excludePath) {
        File dir = new File(dirPath);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File newest = null;
        long newestTime = Long.MIN_VALUE;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.startsWith("rearscreen_") || !name.endsWith(".mra")) continue;
            if (excludePath != null && excludePath.equals(f.getAbsolutePath())) continue;
            if (f.lastModified() > newestTime) {
                newest = f;
                newestTime = f.lastModified();
            }
        }
        return newest;
    }

    @SuppressLint("SetWorldReadable")
    private void grantReadAccess(File file) {
        // 仅设为全局可读（供主题服务跨进程读取）；写权限限所有者，且不置可执行位
        file.setReadable(true, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    private static String readUtf8(@NonNull File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                content.append(buffer, 0, count);
                if (content.length() > MAX_EDIT_CONFIG_BYTES) {
                    throw new IOException("editConfig exceeds size limit");
                }
            }
        }
        return content.toString();
    }

    private static final class ThemeMagicRepairTarget {
        @Nullable
        final String resId;
        @Nullable
        final String applyId;
        @Nullable
        final String editConfigPath;

        private ThemeMagicRepairTarget(
                @Nullable String resId,
                @Nullable String applyId,
                @Nullable String editConfigPath
        ) {
            this.resId = resId;
            this.applyId = applyId;
            this.editConfigPath = editConfigPath;
        }

        @Nullable
        static ThemeMagicRepairTarget from(@Nullable Object bean) {
            if (bean == null) return null;
            String editConfigPath = asString(callNoArgMethodQuietly(bean, "getMamlEditConfigPath"));
            if (isEmpty(editConfigPath) || !isThemeMagicUserPath(editConfigPath)) {
                return null;
            }
            return new ThemeMagicRepairTarget(
                    asString(callNoArgMethodQuietly(bean, "getResId")),
                    asString(callNoArgMethodQuietly(bean, "getApplyId")),
                    editConfigPath);
        }
    }

    private static final class RepairStats {
        int copied;
        int missing;
    }

    @Nullable
    private static String asString(@Nullable Object value) {
        return value instanceof String ? (String) value : null;
    }

    @NonNull
    private static String sanitizeFilePart(@Nullable String value, @NonNull String fallback) {
        if (isEmpty(value)) {
            return fallback;
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    private static boolean isThemeMagicUserPath(@NonNull String path) {
        return path.startsWith(THEME_MAGIC_USERS_DIR);
    }

    private static boolean isThemeMagicPath(@NonNull String path) {
        return path.equals(THEME_MAGIC_DIR) || path.startsWith(THEME_MAGIC_DIR + "/");
    }

    @Nullable
    private static String resolveForegroundPackage(@Nullable Object coverManager) {
        Object powerManagerServiceImpl = getFieldValue(
                coverManager,
                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_FIELD);
        Object packageName = getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_FOREGROUND_APP_PACKAGE_FIELD);
        return packageName instanceof String ? (String) packageName : null;
    }

    @NonNull
    private static String[] resolveForegroundPackages(@Nullable Object powerManagerServiceImpl) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        addPackageName(packages, getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_FOREGROUND_APP_PACKAGE_FIELD));
        Object activityTaskManager = getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_ACTIVITY_TASK_MANAGER_FIELD);
        Object tasks = callMethodQuietly(activityTaskManager, "getTasks", 3, false, false, 0);
        if (tasks instanceof List<?> list) {
            for (Object task : list) {
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_TOP_ACTIVITY_FIELD));
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_BASE_ACTIVITY_FIELD));
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_ORIG_ACTIVITY_FIELD));
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_REAL_ACTIVITY_FIELD));
            }
        }
        return packages.toArray(new String[0]);
    }

    private static void addComponentPackageName(
            @NonNull LinkedHashSet<String> packages,
            @Nullable Object component
    ) {
        if (component instanceof ComponentName) {
            addPackageName(packages, ((ComponentName) component).getPackageName());
        }
    }

    private static void addPackageName(
            @NonNull LinkedHashSet<String> packages,
            @Nullable Object packageName
    ) {
        if (packageName instanceof String value && !value.isEmpty()) {
            packages.add(value);
        }
    }

    @NonNull
    private static String joinPackageNames(@Nullable String[] packageNames) {
        if (packageNames == null || packageNames.length == 0) return "[]";
        StringBuilder builder = new StringBuilder();
        for (String packageName : packageNames) {
            if (packageName == null || packageName.isEmpty()) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(packageName);
        }
        return builder.length() == 0 ? "[]" : builder.toString();
    }

    private boolean safeCopy(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
            return true;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "safeCopy failed", e);
            return false;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static Object getFieldValue(Object target, String field) {
        if (target == null) return null;
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                Field f = owner.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private static boolean setFieldValue(Object target, String field, Object value) {
        if (target == null) return false;
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                Field f = owner.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            } catch (Throwable e) {
                return false;
            }
        }
        return false;
    }

    @Nullable
    private static Object getFieldValueByType(
            @Nullable Object target,
            @NonNull Class<?> expectedType,
            @NonNull String... fieldNames
    ) {
        for (String fieldName : fieldNames) {
            Object value = getFieldValue(target, fieldName);
            if (expectedType.isInstance(value)) {
                return value;
            }
        }
        return null;
    }

    private static Object callMethod(Object target, String method, Object... args) throws Throwable {
        Class<?>[] paramTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) {
                paramTypes[i] = Object.class;
                continue;
            }
            Class<?> c = a.getClass();
            if (c == Boolean.class) c = boolean.class;
            else if (c == Integer.class) c = int.class;
            else if (c == Long.class) c = long.class;
            else if (c == Float.class) c = float.class;
            else if (c == Double.class) c = double.class;
            else if (c == Short.class) c = short.class;
            else if (c == Byte.class) c = byte.class;
            else if (c == Character.class) c = char.class;
            paramTypes[i] = c;
        }
        Method m = findMethod(target.getClass(), method, paramTypes);
        if (m == null) throw new NoSuchMethodException(method);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Nullable
    private static Object callNoArgMethodQuietly(@Nullable Object target, @NonNull String method) {
        if (target == null) return null;
        try {
            return callMethod(target, method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object callMethodQuietly(@Nullable Object target, @NonNull String method, Object... args) {
        if (target == null) return null;
        try {
            return callMethod(target, method, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static String getStaticStringField(ClassLoader classLoader, String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof String) {
                return (String) value;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    private void hookLongPressMethod(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @Nullable Object blockedReturn
    ) {
        hookMethodIfPresent(
                targetClass,
                methodName,
                parameterTypes,
                targetClass.getName() + "#" + methodName,
                chain -> {
                    if (PrefsBridge.shouldBlockLongPressEdit(this)) {
                        return blockedReturn;
                    }
                    return chain.proceed();
                }
        );
    }

    private boolean hookMethodIfPresent(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        return hookMethodIfPresent(findDeclaredMethod(targetClass, methodName, parameterTypes), label, callback);
    }

    private boolean hookMethodIfPresent(
            @Nullable Method method,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        if (method == null) {
            log(Log.WARN, Constants.LOG_TAG, "Hook target missing: " + label);
            return false;
        }
        try {
            hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(callback::onHook);
            log(Log.DEBUG, Constants.LOG_TAG, "Hook installed: " + label);
            return true;
        } catch (Throwable throwable) {
            log(Log.ERROR, Constants.LOG_TAG, "Hook install failed: " + label, throwable);
            return false;
        }
    }

    @Nullable
    private static Class<?> findClass(@NonNull String className, @NonNull ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Nullable
    private static Class<?> findFirstClass(
            @NonNull ClassLoader classLoader,
            @NonNull String... classNames
    ) {
        for (String className : classNames) {
            Class<?> targetClass = findClass(className, classLoader);
            if (targetClass != null) {
                return targetClass;
            }
        }
        return null;
    }

    @Nullable
    private static Method findFirstDeclaredMethod(
            @NonNull Class<?> targetClass,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String... methodNames
    ) {
        for (String methodName : methodNames) {
            Method method = findDeclaredMethod(targetClass, methodName, parameterTypes);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Method findDeclaredMethod(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>... parameterTypes
    ) {
        try {
            Method method = targetClass.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private interface HookCallback {
        Object onHook(Chain chain) throws Throwable;
    }
}
