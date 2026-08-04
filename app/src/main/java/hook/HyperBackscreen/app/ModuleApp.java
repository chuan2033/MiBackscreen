package hook.HyperBackscreen.app;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

import hook.HyperBackscreen.bridge.PrefsBridge;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class ModuleApp extends Application {
    @Nullable
    private static volatile XposedService service;

    private static final CopyOnWriteArrayList<Runnable> serviceListeners = new CopyOnWriteArrayList<>();

    @Nullable
    public static XposedService getService() {
        return service;
    }

    /**
     * 注册服务绑定/断开的回调。UI 层用它在服务就绪后重新读取远程偏好，
     * 取代之前每 500ms 一次的空转轮询。
     */
    public static void addServiceListener(@NonNull Runnable listener) {
        serviceListeners.add(listener);
    }

    public static void removeServiceListener(@NonNull Runnable listener) {
        serviceListeners.remove(listener);
    }

    private static void notifyServiceListeners() {
        for (Runnable r : serviceListeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Context appContext = getApplicationContext();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService s) {
                service = s;
                // 服务就绪后对齐本地与远程偏好，并通知 UI 刷新开关状态
                PrefsBridge.syncOnServiceAvailable(appContext, s);
                notifyServiceListeners();
            }

            @Override
            public void onServiceDied(@NonNull XposedService s) {
                service = null;
                notifyServiceListeners();
            }
        });
    }
}
