package hook.HyperBackscreen.hook;

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
final class AutoWallpaperRotationHost {
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
        private final Activity activity;
        private final VisibilityProbe visibilityProbe;
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
            this.activity = resolveActivity(panel.getContext());
            this.visibilityProbe = activity != null
                    ? new VisibilityProbe(activity, this::onWindowVisibilityChanged)
                    : null;
            this.receiver = activity != null ? new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!Constants.ACTION_SYNC_AUTO_WALLPAPER.equals(intent.getAction())) return;
                    updateInterval(intent.getIntExtra(Constants.EXTRA_INTERVAL_MINUTES, 0));
                }
            } : null;
            attachHostObservers();
        }

        private void attachHostObservers() {
            if (activity == null || visibilityProbe == null || receiver == null) return;
            try {
                View decor = activity.getWindow().getDecorView();
                if (decor instanceof ViewGroup group) {
                    visibilityProbe.setAlpha(0f);
                    visibilityProbe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    group.addView(visibilityProbe, new ViewGroup.LayoutParams(1, 1));
                    windowVisible = visibilityProbe.getWindowVisibility() == View.VISIBLE;
                }
                activity.registerReceiver(
                        receiver,
                        new IntentFilter(Constants.ACTION_SYNC_AUTO_WALLPAPER),
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
            if (visibilityProbe != null) {
                windowVisible = visibilityProbe.getWindowVisibility() == View.VISIBLE;
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
            if (activity != null && receiver != null) {
                try {
                    activity.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
            }
            if (visibilityProbe != null && visibilityProbe.getParent() instanceof ViewGroup parent) {
                parent.removeView(visibilityProbe);
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
