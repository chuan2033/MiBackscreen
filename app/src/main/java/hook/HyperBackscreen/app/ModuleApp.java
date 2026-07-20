package hook.HyperBackscreen.app;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class ModuleApp extends Application {
    @Nullable
    private static volatile XposedService service;

    @Nullable
    public static XposedService getService() {
        return service;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService s) {
                service = s;
            }

            @Override
            public void onServiceDied(@NonNull XposedService s) {
                service = null;
            }
        });
    }
}
