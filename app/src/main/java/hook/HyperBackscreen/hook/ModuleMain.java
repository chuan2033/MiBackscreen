package hook.HyperBackscreen.hook;

import android.app.Activity;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.bridge.DiagnosticLogStore;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.ui.SwipePanelHost;
import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private static final long REAR_SELECTION_PENDING_TIMEOUT_MS = 15_000L;
    private volatile boolean hooksInstalled = false;
    private volatile boolean themeStoreHooksInstalled = false;
    private final Map<Activity, SwipeState> swipeStates = new WeakHashMap<>();
    private final Set<Activity> exclusionApplied = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<View, PendingRearSelection> pendingRearSelections =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 供被 Hook 进程（背屏）内的 PrefsBridge 取远程偏好使用
        PrefsBridge.attachModule(this);
        log(Log.INFO, Constants.LOG_TAG, "onModuleLoaded: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();

        if (Constants.TARGET_PACKAGE.equals(packageName)) {
            if (hooksInstalled) return;
            synchronized (this) {
                if (hooksInstalled) return;
                try {
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
                    themeStoreHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Theme store hooks installed");
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG, "Failed to install theme store hooks", throwable);
                }
            }
        }
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
                    if (PrefsBridge.shouldFixRearScreenApply(this)) {
                        Object bean = getFieldValue(chain.getThisObject(), Constants.THEME_APPLY_BEAN_FIELD);
                        if (bean != null) {
                            promoteReappliedWallpaper(bean, classLoader);
                            fillSnapshotPaths(bean);
                            patchRightsPath(bean, classLoader);
                            patchMtzPath(bean);
                        }
                    }
                    return chain.proceed();
                }
        );
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
        // Room 禁止主线程数据库访问。先在短任务中完成排序落库，再让页面创建或恢复，
        // 确保 LiveData 第一次（或恢复后）渲染拿到当前背屏壁纸。
        Thread syncThread = new Thread(
                () -> syncThemeDatabaseToRearSelection(activity, classLoader),
                "MiBackscreen-RearSelectionSync");
        syncThread.setDaemon(true);
        syncThread.start();
        try {
            syncThread.join(3000L);
            if (syncThread.isAlive()) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Theme settings selection sync timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings selection sync interrupted", e);
        }
    }

    private void syncThemeDatabaseToRearSelection(
            @NonNull Activity activity,
            @NonNull ClassLoader classLoader
    ) {
        try {
            String raw = Settings.Secure.getString(
                    activity.getContentResolver(), Constants.SECURE_THEME_REAR_WIDGET);
            if (isEmpty(raw)) return;
            JSONArray data = new JSONObject(raw).optJSONArray("data");
            JSONObject selectedWidget = data != null ? data.optJSONObject(0) : null;
            if (selectedWidget == null || !selectedWidget.has("id")) return;
            int selectedId = selectedWidget.getInt("id");

            Class<?> managerClass = findClass(Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) return;
            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) return;
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            Object manager = companion != null
                    ? callMethod(companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD)
                    : null;
            if (manager == null) return;
            Object listValue = callMethod(
                    manager, Constants.THEME_REAR_DATA_MANAGER_GET_LIST_METHOD);
            if (!(listValue instanceof List<?> items) || items.isEmpty()) return;

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

            if (selectedItem == null || selectedPosition >= maxPosition
                    || maxPosition == Integer.MAX_VALUE) {
                return;
            }
            int promotedPosition = maxPosition + 1;
            callMethod(selectedItem, "setPosition", promotedPosition);
            callMethod(manager, Constants.THEME_REAR_DATA_MANAGER_UPSERT_METHOD, selectedItem);
            log(Log.INFO, Constants.LOG_TAG,
                    "Synced Theme DB to rear selection: id=" + selectedId
                            + ", position=" + selectedPosition + "->" + promotedPosition);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "syncThemeDatabaseToRearSelection failed", e);
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

    private void grantReadAccess(File file) {
        // 仅设为全局可读（供主题服务跨进程读取）；写权限限所有者，且不置可执行位
        file.setReadable(true, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    private void safeCopy(File src, File dst) {
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
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "safeCopy failed", e);
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

    private void hookMethodIfPresent(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        hookMethodIfPresent(findDeclaredMethod(targetClass, methodName, parameterTypes), label, callback);
    }

    private void hookMethodIfPresent(
            @Nullable Method method,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        if (method == null) {
            log(Log.WARN, Constants.LOG_TAG, "Hook target missing: " + label);
            return;
        }
        try {
            hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(callback::onHook);
            log(Log.DEBUG, Constants.LOG_TAG, "Hook installed: " + label);
        } catch (Throwable throwable) {
            log(Log.ERROR, Constants.LOG_TAG, "Hook install failed: " + label, throwable);
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
