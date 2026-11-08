package hook.HyperBackscreen.hook.subscreen;

import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * {@code MamlView.sendCommand(String)} 的唯一 Hook（同一个方法只能挂一次，视频壁纸和背屏歌词都要用）。
 *
 * <ul>
 *   <li>{@link Interceptor}：命令发出前调用，返回 true 表示这次先不发；需要时自己稍后调用 {@code resend}
 *       重发（重发会再经过这个 Hook）；</li>
 *   <li>{@link Observer}：命令真正发给 MAML 时调用（被拦下的不算，重发时才算）。</li>
 * </ul>
 */
public final class MamlCommandHook {
    private static final String MAML_VIEW = "com.miui.maml.component.MamlView";

    public interface Interceptor {
        boolean intercept(@NonNull Object view, @NonNull String command, @NonNull Runnable resend);
    }

    public interface Observer {
        void onCommand(@Nullable String command);
    }

    private static final CopyOnWriteArrayList<Interceptor> INTERCEPTORS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();
    private static volatile boolean installed;

    private MamlCommandHook() {
    }

    public static void addInterceptor(@NonNull ModuleMain module, @NonNull ClassLoader classLoader,
                                      @NonNull Interceptor interceptor) {
        INTERCEPTORS.addIfAbsent(interceptor);
        install(module, classLoader);
    }

    public static void addObserver(@NonNull ModuleMain module, @NonNull ClassLoader classLoader,
                                   @NonNull Observer observer) {
        OBSERVERS.addIfAbsent(observer);
        install(module, classLoader);
    }

    private static synchronized void install(@NonNull ModuleMain module, @NonNull ClassLoader classLoader) {
        if (installed) return;
        Class<?> mamlView = findClass(MAML_VIEW, classLoader);
        Method sendCommand = mamlView == null ? null : findDeclaredMethod(mamlView, "sendCommand", String.class);
        if (sendCommand == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "MamlView#sendCommand not found");
            return;
        }
        sendCommand.setAccessible(true);
        installed = module.hookMethodIfPresent(sendCommand, MAML_VIEW + "#sendCommand", chain -> {
            Object view = chain.getThisObject();
            Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            String command = arg instanceof String ? (String) arg : null;
            if (view != null && command != null && !INTERCEPTORS.isEmpty()) {
                Runnable resend = () -> {
                    try {
                        sendCommand.invoke(view, command);
                    } catch (Throwable error) {
                        module.log(Log.DEBUG, Constants.LOG_TAG, "Resend MAML command " + command + " failed: " + error);
                    }
                };
                for (Interceptor interceptor : INTERCEPTORS) {
                    try {
                        if (interceptor.intercept(view, command, resend)) return null;
                    } catch (Throwable error) {
                        module.log(Log.DEBUG, Constants.LOG_TAG, "MAML command interceptor failed: " + error);
                    }
                }
            }
            for (Observer observer : OBSERVERS) {
                try {
                    observer.onCommand(command);
                } catch (Throwable ignored) {
                    // 观察者异常不影响命令下发。
                }
            }
            return chain.proceed();
        });
    }
}
