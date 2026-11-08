package hook.HyperBackscreen.hook.subscreen;

import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearWidgetList;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 背屏小部件：把用户在模块里选的桌面小部件作为一页页「原生小部件」放进背屏页面列表。
 *
 * <p>背屏中心本身就支持这种页面：页面模型 SubScreenWidget 的 type 1 带一个 ComponentName，
 * 由宿主自己的 AppWidgetHost 托管（背屏中心持有 BIND_APPWIDGET，绑定不用弹授权）。模块只做两件事：
 * <ul>
 *   <li>{@code MainPanel.f(int, List)}（日志 "onSubScreenWidgetChanged"，首次载入与主题商店改壁纸都走这里）
 *       之前：先删掉列表里模块以前加的页面（extra 带 {@link RearWidgetList#MARKER_KEY}），
 *       再按当前选择追加；</li>
 *   <li>{@code AppWidgetHost.createView} 之后：给模块的小部件留出左侧摄像头挖孔与四周边距，
 *       并按剩下的区域告诉小部件实际尺寸，让它按背屏大小排版。</li>
 * </ul>
 * 选择变化由模块 App 发 {@link Constants#ACTION_SYNC_REAR_WIDGETS} 通知，拿上次的原始列表重新走一遍
 * {@code MainPanel.f}，不用重启背屏中心。
 */
public final class RearAppWidgetHooks {
    private static final String TAG = Constants.LOG_TAG;
    /** 背屏 976x596 左侧 0..296px 是摄像头挖孔；系统没给 DisplayCutout 时按这个兜底。 */
    private static final int KNOWN_SAFE_LEFT_PX = 298;
    private static final float EDGE_MARGIN_DP = 12f;

    private final ModuleMain module;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private volatile Method updateMethod;
    @Nullable private volatile String selectedRaw;
    @NonNull private volatile List<String> selected = Collections.emptyList();

    @NonNull private WeakReference<Object> panelRef = new WeakReference<>(null);
    private int lastSource;
    @Nullable private List<Object> lastBase;
    @NonNull private WeakReference<Context> receiverContext = new WeakReference<>(null);
    @Nullable private BroadcastReceiver receiver;

    public RearAppWidgetHooks(@NonNull ModuleMain module) {
        this.module = module;
    }

    public void install(@NonNull ClassLoader classLoader) {
        try {
            installPanelHook(classLoader);
            installHostViewHook();
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Rear widget hooks failed", error);
        }
    }

    // ---- 页面列表 ----

    private void installPanelHook(@NonNull ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> panelClass = classLoader.loadClass(Constants.HOOK_CLASS_MAIN_PANEL);
        Method method = findUpdateMethod(panelClass);
        if (method == null) {
            module.log(Log.WARN, TAG, "Rear widget: MainPanel(int, List) not found");
            return;
        }
        method.setAccessible(true);
        updateMethod = method;
        module.hookMethodIfPresent(method, "MainPanel#" + method.getName() + "(int,List)", chain -> {
            List<Object> args = new ArrayList<>(chain.getArgs());
            if (args.size() < 2 || !(args.get(0) instanceof Integer) || !(args.get(1) instanceof List)) {
                return chain.proceed();
            }
            Object panel = chain.getThisObject();
            List<?> incoming = (List<?>) args.get(1);
            List<Object> base = stripModulePages(incoming);
            synchronized (this) {
                panelRef = new WeakReference<>(panel);
                lastSource = (Integer) args.get(0);
                lastBase = new ArrayList<>(base);
            }
            if (panel instanceof View) ensureReceiver(((View) panel).getContext());
            List<String> components = currentSelection();
            if (components.isEmpty() && base.size() == incoming.size()) return chain.proceed();
            List<Object> merged = new ArrayList<>(base);
            if (!incoming.isEmpty()) {
                Context context = panel instanceof View ? ((View) panel).getContext() : null;
                merged.addAll(buildPages(incoming.get(0).getClass(), components, context));
            }
            if (merged.isEmpty()) return chain.proceed();
            args.set(1, merged);
            module.log(Log.INFO, TAG, "Rear widget pages: base=" + base.size()
                    + ", widgets=" + (merged.size() - base.size()));
            return chain.proceed(args.toArray());
        });
    }

    @Nullable
    private static Method findUpdateMethod(@NonNull Class<?> panelClass) {
        Method fallback = null;
        for (Method method : panelClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) continue;
            if (types.length != 2 || types[0] != int.class || types[1] != List.class) continue;
            if ("f".equals(method.getName())) return method;
            if (fallback == null) fallback = method;
        }
        return fallback;
    }

    @NonNull
    private List<String> currentSelection() {
        String raw = PrefsBridge.readRearAppWidgetsForHook(module);
        return selectionFrom(raw);
    }

    @NonNull
    private List<String> selectionFrom(@Nullable String raw) {
        String value = raw == null ? "" : raw;
        if (value.equals(selectedRaw)) return selected;
        List<String> parsed = Collections.unmodifiableList(RearWidgetList.parse(value));
        selected = parsed;
        selectedRaw = value;
        return parsed;
    }

    /** 去掉模块以前加的页面（它们会被宿主存回 widget.json，下次载入又回来）。 */
    @NonNull
    private static List<Object> stripModulePages(@NonNull List<?> incoming) {
        List<Object> result = new ArrayList<>(incoming.size());
        for (Object page : incoming) {
            if (!isModulePage(page)) result.add(page);
        }
        return result;
    }

    private static boolean isModulePage(@Nullable Object page) {
        if (page == null) return false;
        for (Field field : page.getClass().getDeclaredFields()) {
            if (field.getType() != Bundle.class || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(page);
                if (value instanceof Bundle && ((Bundle) value).containsKey(RearWidgetList.MARKER_KEY)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 按宿主页面模型的 13 参构造器造 type 1 页面：
     * (id, type, changed, extra, nfc, path, configPath, componentName, options, cmf, ?, ?, ?)。
     * 构造器形状对不上（宿主改版）就不加，原列表照常显示。
     */
    @NonNull
    private List<Object> buildPages(@NonNull Class<?> pageClass, @NonNull List<String> components,
                                    @Nullable Context context) {
        if (components.isEmpty()) return Collections.emptyList();
        Constructor<?> constructor = findPageConstructor(pageClass);
        if (constructor == null) {
            module.log(Log.WARN, TAG, "Rear widget: page constructor not found in " + pageClass.getName());
            return Collections.emptyList();
        }
        Set<ComponentName> installed = installedProviders(context);
        List<Object> pages = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            ComponentName component = ComponentName.unflattenFromString(components.get(i));
            if (component == null) continue;
            if (installed != null && !installed.contains(component)) {
                module.log(Log.INFO, TAG, "Rear widget skipped, provider not installed: " + components.get(i));
                continue;
            }
            Bundle extra = new Bundle();
            extra.putBoolean(RearWidgetList.MARKER_KEY, true);
            String label = providerLabel(context, component);
            if (label != null) extra.putString("resName", label);
            try {
                pages.add(constructor.newInstance(
                        RearWidgetList.idFor(i), 1, 1, extra, false, null, null, component,
                        null, null, false, false, -1));
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, "Rear widget page create failed: " + component, error);
            }
        }
        return pages;
    }

    @Nullable
    private static Constructor<?> findPageConstructor(@NonNull Class<?> pageClass) {
        Class<?>[] expected = {
                int.class, int.class, int.class, Bundle.class, boolean.class, String.class, String.class,
                ComponentName.class, Bundle.class, Bundle.class, boolean.class, boolean.class, int.class,
        };
        for (Constructor<?> constructor : pageClass.getDeclaredConstructors()) {
            if (java.util.Arrays.equals(constructor.getParameterTypes(), expected)) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    @Nullable
    private static Set<ComponentName> installedProviders(@Nullable Context context) {
        if (context == null) return null;
        try {
            Set<ComponentName> result = new HashSet<>();
            for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(context).getInstalledProviders()) {
                result.add(info.provider);
            }
            return result;
        } catch (Throwable error) {
            return null;
        }
    }

    @Nullable
    private static String providerLabel(@Nullable Context context, @NonNull ComponentName component) {
        if (context == null) return null;
        try {
            for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(context).getInstalledProviders()) {
                if (component.equals(info.provider)) return info.loadLabel(context.getPackageManager());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---- 即时同步 ----

    private void ensureReceiver(@Nullable Context context) {
        if (context == null) return;
        synchronized (this) {
            if (receiverContext.get() == context && receiver != null) return;
            Context old = receiverContext.get();
            if (old != null && receiver != null) {
                try {
                    old.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
            }
            BroadcastReceiver next = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (!Constants.ACTION_SYNC_REAR_WIDGETS.equals(intent.getAction())) return;
                    selectionFrom(intent.getStringExtra(Constants.EXTRA_PREFERENCE_VALUE));
                    reapply();
                }
            };
            try {
                // 只收持有模块签名权限的发送方（模块 App 自己）。
                context.registerReceiver(next, new IntentFilter(Constants.ACTION_SYNC_REAR_WIDGETS),
                        Constants.PERMISSION_SYNC_HOST, null, Context.RECEIVER_EXPORTED);
                receiver = next;
                receiverContext = new WeakReference<>(context);
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, "Rear widget receiver register failed", error);
            }
        }
    }

    /** 拿最近一次宿主给的原始页面列表重新走一遍 MainPanel 的更新入口，Hook 会按新选择重新拼。 */
    private void reapply() {
        mainHandler.post(() -> {
            Object panel;
            List<Object> base;
            int source;
            synchronized (this) {
                panel = panelRef.get();
                base = lastBase;
                source = lastSource;
            }
            Method method = updateMethod;
            if (panel == null || base == null || base.isEmpty() || method == null) return;
            try {
                method.invoke(panel, source, new ArrayList<>(base));
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, "Rear widget reapply failed", error);
            }
        });
    }

    // ---- 小部件视图 ----

    private void installHostViewHook() {
        Method createView = findDeclaredMethod(AppWidgetHost.class, "createView",
                Context.class, int.class, AppWidgetProviderInfo.class);
        module.hookMethodIfPresent(createView, "AppWidgetHost#createView", chain -> {
            Object result = chain.proceed();
            Object info = chain.getArgs().size() > 2 ? chain.getArgs().get(2) : null;
            if (result instanceof AppWidgetHostView && info instanceof AppWidgetProviderInfo
                    && selected.contains(((AppWidgetProviderInfo) info).provider.flattenToString())) {
                fitToRearScreen((AppWidgetHostView) result);
            }
            return result;
        });
    }

    /**
     * 让小部件只占摄像头挖孔右侧的安全区：左边距 = 挖孔安全区 + 边距，其余三边留边距
     * （右侧两个屏幕圆角也在边距内）。内容区尺寸变化时用 updateAppWidgetSize 告诉小部件，
     * 支持多尺寸布局的小部件会换成合适的版式。
     */
    private static void fitToRearScreen(@NonNull AppWidgetHostView view) {
        view.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) params).gravity = Gravity.CENTER;
                    child.setLayoutParams(params);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        final int[] lastBox = {-1, -1};
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width <= 0 || height <= 0) return;
            float density = v.getResources().getDisplayMetrics().density;
            int margin = Math.round(EDGE_MARGIN_DP * density);
            int padLeft = safeLeftInset(v, width, height) + margin;
            if (v.getPaddingLeft() != padLeft || v.getPaddingTop() != margin
                    || v.getPaddingRight() != margin || v.getPaddingBottom() != margin) {
                v.setPadding(padLeft, margin, margin, margin);
            }
            int boxWidth = Math.max(0, width - padLeft - margin);
            int boxHeight = Math.max(0, height - margin * 2);
            if (boxWidth == lastBox[0] && boxHeight == lastBox[1]) return;
            lastBox[0] = boxWidth;
            lastBox[1] = boxHeight;
            int widthDp = Math.round(boxWidth / density);
            int heightDp = Math.round(boxHeight / density);
            v.post(() -> {
                try {
                    ((AppWidgetHostView) v).updateAppWidgetSize(new Bundle(), widthDp, heightDp, widthDp, heightDp);
                } catch (Throwable error) {
                    Log.w(TAG, "Rear widget size update failed", error);
                }
            });
        });
    }

    private static int safeLeftInset(@NonNull View view, int width, int height) {
        WindowInsets insets = view.getRootWindowInsets();
        DisplayCutout cutout = insets != null ? insets.getDisplayCutout() : null;
        if (cutout != null && cutout.getSafeInsetLeft() > 0) return cutout.getSafeInsetLeft();
        // 这台设备的背屏横屏 976x596，左侧挖孔贯穿全高；拿不到 Insets 时按已知尺寸兜底。
        return width > height && width >= 900 && height <= 700 ? KNOWN_SAFE_LEFT_PX : 0;
    }
}
