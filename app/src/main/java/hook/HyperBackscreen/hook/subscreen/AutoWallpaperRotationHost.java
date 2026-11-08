package hook.HyperBackscreen.hook.subscreen;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import io.github.libxposed.api.XposedModule;

/** Keeps interval rotation aligned with the rear-screen window's actual visibility. */
public final class AutoWallpaperRotationHost {
    private static final long RETRY_DELAY_MS = 15_000L;
    private static final Map<View, Session> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    interface RotationCallback {
        /**
         * @param order 挑图方式（[AutoWallpaperRotationPolicy.ORDER_SEQUENTIAL] /
         *              [AutoWallpaperRotationPolicy.ORDER_RANDOM]）。由宿主每次现读，
         *              用户在设置页改完不用等下一次广播就生效。
         * @return 是否已经成功发起切换；false 会让调度器 15 秒后重试。
         */
        boolean rotate(@NonNull View panel, @NonNull String order);
    }

    private AutoWallpaperRotationHost() {
    }

    static void onPanelResumed(
            @NonNull View panel,
            @NonNull XposedModule module,
            @NonNull RotationCallback callback
    ) {
        Session session;
        synchronized (SESSIONS) {
            session = SESSIONS.get(panel);
            if (session == null) {
                session = new Session(panel, module, callback);
                SESSIONS.put(panel, session);
            }
        }
        session.setResumed(true);
    }

    static void onPanelPaused(@NonNull View panel) {
        Session session = session(panel);
        if (session != null) session.setResumed(false);
    }

    static void onPanelStateChanged(@NonNull View panel) {
        Session session = session(panel);
        if (session != null) session.refreshVisibilityAndSchedule();
    }

    static void onWallpaperCommitted(@NonNull View panel) {
        Session session = session(panel);
        if (session != null) session.markWallpaperCommitted();
    }

    static void release(@NonNull View panel) {
        Session session;
        synchronized (SESSIONS) {
            session = SESSIONS.remove(panel);
        }
        if (session != null) session.release();
    }

    /**
     * 背屏快捷面板改了换壁纸间隔后直接通知本进程里的定时器（面板就在同一进程，
     * 不需要也不能走广播：同步广播要求发送方持有模块的签名权限）。
     */
    public static void updateIntervalForAll(int minutes) {
        Session[] sessions;
        synchronized (SESSIONS) {
            sessions = SESSIONS.values().toArray(new Session[0]);
        }
        for (Session session : sessions) session.updateInterval(minutes);
    }

    @Nullable
    private static Session session(@NonNull View panel) {
        synchronized (SESSIONS) {
            return SESSIONS.get(panel);
        }
    }

    @Nullable
    private static Activity resolveActivity(@NonNull Context source) {
        Context context = source;
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private static final class Session {
        private final WeakReference<View> panel;
        private final XposedModule module;
        private final RotationCallback callback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::onTick;
        // Activity 与探针都只弱引用：Session 是 WeakHashMap 的值，强引用 Activity 会经 decor
        // 反向引用到作为键的 panel，条目就永远回收不掉（destroy Hook 没触发时整页泄漏）。
        private final WeakReference<Activity> activity;
        private final WeakReference<VisibilityProbe> visibilityProbe;
        private final BroadcastReceiver receiver;
        private long lastSwitchElapsed = SystemClock.elapsedRealtime();
        private boolean resumed;
        private boolean windowVisible;
        private boolean released;
        @Nullable
        private Integer intervalOverrideMinutes;

        Session(
                @NonNull View panel,
                @NonNull XposedModule module,
                @NonNull RotationCallback callback
        ) {
            this.panel = new WeakReference<>(panel);
            this.module = module;
            this.callback = callback;
            Activity host = resolveActivity(panel.getContext());
            this.activity = new WeakReference<>(host);
            this.visibilityProbe = new WeakReference<>(host != null
                    ? new VisibilityProbe(host, this::onWindowVisibilityChanged)
                    : null);
            this.receiver = host != null ? new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!Constants.ACTION_SYNC_AUTO_WALLPAPER.equals(intent.getAction())) return;
                    updateInterval(intent.getIntExtra(Constants.EXTRA_INTERVAL_MINUTES, 0));
                }
            } : null;
            attachHostObservers();
        }

        private void attachHostObservers() {
            Activity host = activity.get();
            VisibilityProbe probe = visibilityProbe.get();
            if (host == null || probe == null || receiver == null) return;
            try {
                View decor = host.getWindow().getDecorView();
                if (decor instanceof ViewGroup group) {
                    probe.setAlpha(0f);
                    probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    group.addView(probe, new ViewGroup.LayoutParams(1, 1));
                    windowVisible = probe.getWindowVisibility() == View.VISIBLE;
                }
                // 只收持有模块签名权限的发送方（模块 App 自己），第三方应用发同名广播无效。
                host.registerReceiver(
                        receiver,
                        new IntentFilter(Constants.ACTION_SYNC_AUTO_WALLPAPER),
                        Constants.PERMISSION_SYNC_HOST,
                        null,
                        Context.RECEIVER_EXPORTED);
            } catch (Throwable error) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "Auto wallpaper visibility observer unavailable", error);
            }
        }

        void setResumed(boolean value) {
            if (released) return;
            resumed = value;
            refreshVisibilityAndSchedule();
        }

        void refreshVisibilityAndSchedule() {
            if (released) return;
            View target = panel.get();
            if (target == null) {
                release();
                return;
            }
            VisibilityProbe probe = visibilityProbe.get();
            if (probe != null) {
                windowVisible = probe.getWindowVisibility() == View.VISIBLE;
            } else {
                windowVisible = target.getWindowVisibility() == View.VISIBLE;
            }
            schedule();
        }

        void markWallpaperCommitted() {
            if (released) return;
            lastSwitchElapsed = SystemClock.elapsedRealtime();
            schedule();
        }

        void updateInterval(int minutes) {
            if (released) return;
            intervalOverrideMinutes = AutoWallpaperRotationPolicy.normalizeIntervalMinutes(minutes);
            lastSwitchElapsed = SystemClock.elapsedRealtime();
            schedule();
        }

        private void onWindowVisibilityChanged(boolean visible) {
            windowVisible = visible;
            schedule();
        }

        private void schedule() {
            handler.removeCallbacks(tick);
            View target = panel.get();
            if (released || target == null || !isVisible(target)) return;
            int minutes = intervalMinutes();
            if (minutes == AutoWallpaperRotationPolicy.OFF) return;
            long delay = AutoWallpaperRotationPolicy.remainingDelayMillis(
                    lastSwitchElapsed,
                    SystemClock.elapsedRealtime(),
                    minutes);
            handler.postDelayed(tick, delay);
        }

        private boolean isVisible(@NonNull View target) {
            return resumed
                    && windowVisible
                    && target.isAttachedToWindow()
                    && target.isShown()
                    && target.getWindowVisibility() == View.VISIBLE;
        }

        private int intervalMinutes() {
            Integer override = intervalOverrideMinutes;
            return override != null
                    ? override
                    : PrefsBridge.readAutoWallpaperIntervalMinutes(module);
        }

        private void onTick() {
            if (released) return;
            View target = panel.get();
            if (target == null) {
                release();
                return;
            }
            int minutes = intervalMinutes();
            if (minutes == AutoWallpaperRotationPolicy.OFF || !isVisible(target)) {
                schedule();
                return;
            }
            if (callback.rotate(target, PrefsBridge.readAutoWallpaperOrder(module))) {
                lastSwitchElapsed = SystemClock.elapsedRealtime();
                schedule();
            } else {
                handler.postDelayed(tick, RETRY_DELAY_MS);
            }
        }

        void release() {
            if (released) return;
            released = true;
            handler.removeCallbacks(tick);
            Activity host = activity.get();
            if (host != null && receiver != null) {
                try {
                    host.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
            }
            VisibilityProbe probe = visibilityProbe.get();
            if (probe != null && probe.getParent() instanceof ViewGroup parent) {
                parent.removeView(probe);
            }
        }
    }

    private static final class VisibilityProbe extends View {
        private final java.util.function.Consumer<Boolean> listener;

        VisibilityProbe(
                @NonNull Context context,
                @NonNull java.util.function.Consumer<Boolean> listener
        ) {
            super(context);
            this.listener = listener;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            listener.accept(getWindowVisibility() == VISIBLE);
        }

        @Override
        protected void onDetachedFromWindow() {
            listener.accept(false);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            listener.accept(visibility == VISIBLE);
        }
    }
}
