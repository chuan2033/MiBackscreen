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
import android.view.View;

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
 * 背屏小部件：把用户在模块里选的桌面小部件放进背屏下拉出来的「智能助理」面板。
 *
 * <p>面板（SmartAssistantPanel）和壁纸列表（MainPanel）收同一套页面对象，宿主的页面工厂
 * {@code q2.v.g(SubScreenWidget)} 对 type 1 直接给出原生小部件页，由宿主自己的 AppWidgetHost 托管
 * （背屏中心持有 BIND_APPWIDGET，绑定不弹授权）。模块做的事：
 * <ul>
 *   <li>面板初始化后主动推一次列表；之后每次面板列表更新（{@code f(int, List)}，通知卡片变化都会走）
 *       都先删掉模块旧页、再按当前选择追加（extra 带 {@link RearWidgetList#MARKER_KEY}）；</li>
 *   <li>面板会向每页要一个「卡片状态」接口（通知卡才有），给模块页补一个全 false 的实现；</li>
 *   <li>{@code AppWidgetHost.createView}：换一个不带 AppCompat 布局工厂的 Context 展开 RemoteViews；
 *       {@code onCreateView} 换成 {@link RearWidgetHostView}：按小部件自己的尺寸排版、等比缩放进卡片安全区；</li>
 *   <li>面板吃掉了触摸事件：识别落在小部件上的轻点并补发给它；点开的应用放到主屏；</li>
 *   <li>宿主进程被杀时原生小部件页不释放 id：建视图前清掉同一 hostId 下的旧 id。</li>
 * </ul>
 * 选择变化由模块 App 发 {@link Constants#ACTION_SYNC_REAR_WIDGETS} 通知，拿上次的列表重新走一遍
 * {@code f}，不用重启背屏中心。
 */
public final class RearAppWidgetHooks {
    private static final String TAG = Constants.LOG_TAG;

    private final ModuleMain module;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private volatile Method updateMethod;
    @Nullable private volatile String selectedRaw;
    /**
     * 模块 App 广播带来的最新选择。App 写偏好和发广播几乎同时，偏好同步到这个进程有延迟，
     * 广播到达时读偏好还是旧值（表现为加了小部件要重启背屏中心才出现）；在两边一致之前以它为准。
     */
    @Nullable private volatile String pushedRaw;
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
            installTapForwarding(classLoader);
            installLaunchOnMainDisplay();
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Rear widget hooks failed", error);
        }
    }

    // ---- 页面列表 ----

    private void installPanelHook(@NonNull ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> panelClass = classLoader.loadClass(Constants.HOOK_CLASS_SMART_ASSISTANT_PANEL);
        panelFactory = panelPageFactory(panelClass);
        module.log(Log.INFO, TAG, "Rear widget panel factory: " + (panelFactory == null ? "none"
                : panelFactory.modelClass.getName() + " via " + panelFactory.wrap));
        if (panelFactory != null && panelFactory.wrap != null) installCardStateStub(panelFactory);
        Method method = findUpdateMethod(panelClass);
        if (method == null) {
            module.log(Log.WARN, TAG, "Rear widget: SmartAssistantPanel(int, List) not found");
            return;
        }
        method.setAccessible(true);
        updateMethod = method;
        Method capture = null;
        for (Method candidate : panelClass.getDeclaredMethods()) {
            if ("setNotificationPanel".equals(candidate.getName()) && candidate.getParameterCount() == 1) {
                capture = candidate;
                break;
            }
        }
        module.hookMethodIfPresent(capture,
                "SmartAssistantPanel#setNotificationPanel", chain -> {
                    Object result = chain.proceed();
                    Object panel = chain.getThisObject();
                    synchronized (this) {
                        if (panelRef.get() != panel) {
                            panelRef = new WeakReference<>(panel);
                            if (lastBase == null) {
                                lastBase = new ArrayList<>();
                                lastSource = 0;
                            }
                        }
                    }
                    if (panel instanceof View) ensureReceiver(((View) panel).getContext());
                    module.log(Log.INFO, TAG, "Rear widget: assistant panel captured");
                    mainHandler.postDelayed(this::reapply, 3_000L);
                    return result;
                });
        module.hookMethodIfPresent(method, "SmartAssistantPanel#" + method.getName() + "(int,List)", chain -> {
            List<Object> args = new ArrayList<>(chain.getArgs());
            if (args.size() < 2 || !(args.get(0) instanceof Integer) || !(args.get(1) instanceof List)) {
                return chain.proceed();
            }
            Object panel = chain.getThisObject();
            List<?> incoming = (List<?>) args.get(1);
            PageFactory factory = incoming.isEmpty() ? panelFactory : pageFactory(incoming.get(0).getClass());
            List<Object> base = stripModulePages(incoming, factory);
            synchronized (this) {
                panelRef = new WeakReference<>(panel);
                lastSource = (Integer) args.get(0);
                lastBase = new ArrayList<>(base);
            }
            if (panel instanceof View) ensureReceiver(((View) panel).getContext());
            List<String> components = currentSelection();
            if (components.isEmpty() && base.size() == incoming.size()) return chain.proceed();
            List<Object> merged = new ArrayList<>(base);
            Context context = panel instanceof View ? ((View) panel).getContext() : null;
            merged.addAll(buildPages(factory, panel.getClass(), components, context));
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
        String prefs = PrefsBridge.readRearAppWidgetsForHook(module);
        String pushed = pushedRaw;
        if (pushed != null) {
            // 偏好已经同步过来（和广播带来的一致），以后照常读偏好。
            if (pushed.equals(prefs == null ? "" : prefs)) pushedRaw = null;
            return selectionFrom(pushed);
        }
        return selectionFrom(prefs);
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
    private static List<Object> stripModulePages(@NonNull List<?> incoming, @Nullable PageFactory factory) {
        List<Object> result = new ArrayList<>(incoming.size());
        for (Object page : incoming) {
            if (!isModulePage(page, factory)) result.add(page);
        }
        return result;
    }

    /** 列表里是页面对象时，标记在它持有的页面模型的 extra 里；是页面模型时直接在自身。 */
    private static boolean isModulePage(@Nullable Object page, @Nullable PageFactory factory) {
        if (page == null) return false;
        if (hasMarker(page)) return true;
        if (factory == null || factory.wrap == null) return false;
        for (Class<?> type = page.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != factory.modelClass) continue;
                try {
                    field.setAccessible(true);
                    if (hasMarker(field.get(page))) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean hasMarker(@Nullable Object holder) {
        if (holder == null) return false;
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != Bundle.class || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof Bundle && ((Bundle) value).containsKey(RearWidgetList.MARKER_KEY)) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /**
     * 怎么造一页：页面模型（SubScreenWidget）的 13 参构造器，外加可选的「模型 → 页面对象」静态工厂。
     * 面板的 {@code f} 收到的是页面对象（MAML 页、小部件页等共同父类的子类），父类上有
     * {@code static Page g(SubScreenWidget)} 按 type 选具体页面类；直接收模型的老版本就不需要包装。
     */
    private static final class PageFactory {
        @NonNull final Class<?> modelClass;
        @NonNull final Constructor<?> modelConstructor;
        @Nullable final Method wrap;

        PageFactory(@NonNull Class<?> modelClass, @NonNull Constructor<?> modelConstructor, @Nullable Method wrap) {
            this.modelClass = modelClass;
            this.modelConstructor = modelConstructor;
            this.wrap = wrap;
        }

        @NonNull
        Object create(Object... modelArgs) throws Exception {
            Object model = modelConstructor.newInstance(modelArgs);
            return wrap == null ? model : wrap.invoke(null, model);
        }
    }

    @Nullable private volatile PageFactory panelFactory;

    /**
     * 智能助理面板会问每一页要一个「卡片状态」接口（三个 boolean 方法，通知卡才有），
     * 原生小部件页返回 null 会让面板空指针。给模块的页面补一个全 false 的实现。
     */
    private void installCardStateStub(@NonNull PageFactory factory) {
        Class<?> pageBase = factory.wrap.getDeclaringClass();
        for (Method method : pageBase.getDeclaredMethods()) {
            Class<?> type = method.getReturnType();
            if (method.getParameterCount() != 0 || !type.isInterface()) continue;
            Method[] members = type.getDeclaredMethods();
            boolean allBoolean = members.length > 0;
            for (Method member : members) {
                allBoolean &= member.getReturnType() == boolean.class && member.getParameterCount() == 0;
            }
            if (!allBoolean) continue;
            Object stub = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, m, a) -> {
                        if (m.getReturnType() == boolean.class) return false;
                        if ("hashCode".equals(m.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(m.getName())) return a != null && a.length == 1 && a[0] == proxy;
                        if ("toString".equals(m.getName())) return "MiBackscreenCardState";
                        return null;
                    });
            module.hookMethodIfPresent(method, "Page#" + method.getName() + " card state", chain -> {
                Object result = chain.proceed();
                if (result == null && isModulePage(chain.getThisObject(), factory)) return stub;
                return result;
            });
            module.log(Log.INFO, TAG, "Rear widget: card state stub on " + method);
        }
    }

    /** 面板列表可能还是空的：从面板字段里找页面父类（带「模型 → 页面」静态工厂的那个）。 */
    @Nullable
    private static PageFactory panelPageFactory(@NonNull Class<?> panelClass) {
        for (Field field : panelClass.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (type.isPrimitive() || type.isArray() || type.getName().startsWith("java.")
                    || type.getName().startsWith("android.")) continue;
            PageFactory factory = resolvePageFactory(type);
            if (factory != null && factory.wrap != null) return factory;
        }
        return null;
    }

    @Nullable private static volatile PageFactory cachedFactory;
    @Nullable private static volatile Class<?> cachedFactoryFor;

    @Nullable
    private static PageFactory pageFactory(@NonNull Class<?> elementClass) {
        if (elementClass == cachedFactoryFor) return cachedFactory;
        PageFactory factory = resolvePageFactory(elementClass);
        cachedFactory = factory;
        cachedFactoryFor = elementClass;
        return factory;
    }

    @Nullable
    private static PageFactory resolvePageFactory(@NonNull Class<?> elementClass) {
        Constructor<?> direct = findPageConstructor(elementClass);
        if (direct != null) return new PageFactory(elementClass, direct, null);
        for (Class<?> type = elementClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !method.getReturnType().isAssignableFrom(elementClass)) continue;
                if (method.getReturnType() == Object.class) continue;
                Constructor<?> constructor = findPageConstructor(params[0]);
                if (constructor == null) continue;
                method.setAccessible(true);
                return new PageFactory(params[0], constructor, method);
            }
        }
        return null;
    }

    /**
     * 按宿主页面模型的 13 参构造器造 type 1 页面：
     * (id, type, changed, extra, nfc, path, configPath, componentName, options, cmf, ?, ?, ?)。
     * 构造器形状对不上（宿主改版）就不加，原列表照常显示。
     */
    @NonNull
    private List<Object> buildPages(@Nullable PageFactory factory, @NonNull Class<?> elementClass,
                                    @NonNull List<String> components, @Nullable Context context) {
        if (components.isEmpty()) return Collections.emptyList();
        if (factory == null) {
            module.log(Log.WARN, TAG, "Rear widget: page factory not found for " + elementClass.getName());
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
                Object page = factory.create(
                        RearWidgetList.idFor(i), 1, 1, extra, false, null, null, component,
                        null, null, false, false, -1);
                pages.add(page);
                ensureStaleIdCleanup(page.getClass(), factory);
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, "Rear widget page create failed: " + component, error);
            }
        }
        return pages;
    }

    private volatile boolean staleCleanupInstalled;

    /**
     * 宿主的原生小部件页每次建视图都 {@code new AppWidgetHost(ctx, 计数器++)}、分配一个新 id，
     * 只有页面正常销毁才删。背屏中心进程被杀（更新模块、系统回收）时 id 就留在系统里越积越多。
     * 模块的页建视图前，先把它这次要用的 hostId 下的旧 id 全删掉：同一进程里计数器只增不减，
     * 这个 hostId 下已有的 id 只可能来自之前的进程。
     */
    private void ensureStaleIdCleanup(@NonNull Class<?> pageClass, @NonNull PageFactory factory) {
        if (staleCleanupInstalled) return;
        staleCleanupInstalled = true;
        Field counter = null;
        for (Field field : pageClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == java.util.concurrent.atomic.AtomicInteger.class) {
                counter = field;
                break;
            }
        }
        Method createView = null;
        for (Method method : pageClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == Context.class && View.class.isAssignableFrom(method.getReturnType())) {
                createView = method;
                break;
            }
        }
        if (counter == null || createView == null) {
            module.log(Log.WARN, TAG, "Rear widget: stale id cleanup unavailable in " + pageClass.getName());
            return;
        }
        counter.setAccessible(true);
        Field hostCounter = counter;
        module.hookMethodIfPresent(createView, "Widget page#" + createView.getName() + " stale ids", chain -> {
            Object context = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            if (context instanceof Context && isModulePage(chain.getThisObject(), factory)) {
                try {
                    int hostId = ((java.util.concurrent.atomic.AtomicInteger) hostCounter.get(null)).get();
                    AppWidgetHost host = new AppWidgetHost((Context) context, hostId);
                    int stale = host.getAppWidgetIds().length;
                    if (stale > 0) {
                        host.deleteHost();
                        module.log(Log.INFO, TAG, "Rear widget: removed " + stale + " stale ids of host " + hostId);
                    }
                } catch (Throwable error) {
                    module.log(Log.WARN, TAG, "Rear widget stale id cleanup failed", error);
                }
            }
            return chain.proceed();
        });
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
                    String value = intent.getStringExtra(Constants.EXTRA_PREFERENCE_VALUE);
                    if (value != null) pushedRaw = value;
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

    /** 拿最近一次宿主给的原始页面列表重新走一遍面板的更新入口，Hook 会按新选择重新拼。 */
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
            if (panel == null || base == null || method == null) return;
            try {
                method.invoke(panel, source, new ArrayList<>(base));
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, "Rear widget reapply failed", error);
            }
        });
    }

    // ---- 小部件视图 ----

    /** 模块小部件的视图（弱引用），轻点转发和主屏打开都靠它认出自己的小部件。 */
    private final java.util.Map<AppWidgetHostView, Boolean> hostViews = new java.util.WeakHashMap<>();

    @Nullable private AppWidgetHostView tapTarget;
    private float tapDownX;
    private float tapDownY;
    private long tapDownTime;

    /**
     * 背屏中心在 {@code SubScreenLauncher.dispatchTouchEvent} 里先把事件交给自己的手势管理器，
     * 下拉面板打开时整串事件都被它吃掉，小部件收不到。这里只认「轻点」：按下和抬起都落在模块的小部件上、
     * 位移不超过 touchSlop、时间短于长按，就在抬起后给小部件补发一次按下 + 抬起；滑动照常交给面板。
     */
    private void installTapForwarding(@NonNull ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> launcher = classLoader.loadClass(Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS);
        Method dispatch = findDeclaredMethod(launcher, "dispatchTouchEvent", android.view.MotionEvent.class);
        module.hookMethodIfPresent(dispatch, "SubScreenLauncher#dispatchTouchEvent", chain -> {
            Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            if (arg instanceof android.view.MotionEvent) trackTap((android.view.MotionEvent) arg);
            return chain.proceed();
        });
    }

    private void trackTap(@NonNull android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                tapTarget = widgetAt(event.getRawX(), event.getRawY());
                tapDownX = event.getRawX();
                tapDownY = event.getRawY();
                tapDownTime = event.getEventTime();
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (tapTarget != null) {
                    int slop = android.view.ViewConfiguration.get(tapTarget.getContext()).getScaledTouchSlop();
                    if (Math.abs(event.getRawX() - tapDownX) > slop || Math.abs(event.getRawY() - tapDownY) > slop) {
                        tapTarget = null;
                    }
                }
                break;
            case android.view.MotionEvent.ACTION_UP: {
                AppWidgetHostView target = tapTarget;
                tapTarget = null;
                if (target == null || event.getEventTime() - tapDownTime
                        > android.view.ViewConfiguration.getLongPressTimeout()) break;
                if (widgetAt(event.getRawX(), event.getRawY()) != target) break;
                float x = tapDownX;
                float y = tapDownY;
                mainHandler.post(() -> sendTap(target, x, y));
                break;
            }
            case android.view.MotionEvent.ACTION_CANCEL:
                tapTarget = null;
                break;
            default:
                break;
        }
    }

    @Nullable
    private AppWidgetHostView widgetAt(float rawX, float rawY) {
        synchronized (hostViews) {
            android.graphics.Rect rect = new android.graphics.Rect();
            for (AppWidgetHostView view : hostViews.keySet()) {
                if (view == null || !view.isShown() || !view.getGlobalVisibleRect(rect)) continue;
                if (rect.contains(Math.round(rawX), Math.round(rawY))) return view;
            }
        }
        return null;
    }

    private static void sendTap(@NonNull View target, float rawX, float rawY) {
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        float x = rawX - location[0];
        float y = rawY - location[1];
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50,
                android.view.MotionEvent.ACTION_UP, x, y, 0);
        try {
            target.dispatchTouchEvent(down);
            target.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    /** 小部件点开的应用放到主屏：背屏太小，而且大多数应用在背屏上显示不正常。 */
    private void installLaunchOnMainDisplay() throws ClassNotFoundException {
        Class<?> response = Class.forName("android.widget.RemoteViews$RemoteResponse");
        Method launchOptions = findDeclaredMethod(response, "getLaunchOptions", View.class);
        module.hookMethodIfPresent(launchOptions, "RemoteResponse#getLaunchOptions", chain -> {
            Object result = chain.proceed();
            Object view = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            if (!(result instanceof android.util.Pair) || !(view instanceof View) || !isInModuleWidget((View) view)) {
                return result;
            }
            android.util.Pair<?, ?> pair = (android.util.Pair<?, ?>) result;
            android.app.ActivityOptions options = pair.second instanceof android.app.ActivityOptions
                    ? (android.app.ActivityOptions) pair.second : android.app.ActivityOptions.makeBasic();
            options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
            return android.util.Pair.create(pair.first, options);
        });
    }

    private boolean isInModuleWidget(@NonNull View view) {
        synchronized (hostViews) {
            for (View p = view; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                if (p instanceof AppWidgetHostView && hostViews.containsKey(p)) return true;
            }
        }
        return false;
    }

    private void installHostViewHook() {
        Method createView = findDeclaredMethod(AppWidgetHost.class, "createView",
                Context.class, int.class, AppWidgetProviderInfo.class);
        module.hookMethodIfPresent(createView, "AppWidgetHost#createView", chain -> {
            Object info = chain.getArgs().size() > 2 ? chain.getArgs().get(2) : null;
            Object context = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            if (!isSelected(info) || !(context instanceof Context)) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            args[0] = widgetContext((Context) context);
            Object result = chain.proceed(args);
            if (result instanceof AppWidgetHostView) {
                synchronized (hostViews) {
                    hostViews.put((AppWidgetHostView) result, Boolean.TRUE);
                }
            }
            return result;
        });
        // 模块的小部件换成按自然尺寸排版、等比缩放进卡片安全区的视图。
        Method onCreateView = findDeclaredMethod(AppWidgetHost.class, "onCreateView",
                Context.class, int.class, AppWidgetProviderInfo.class);
        module.hookMethodIfPresent(onCreateView, "AppWidgetHost#onCreateView", chain -> {
            Object info = chain.getArgs().size() > 2 ? chain.getArgs().get(2) : null;
            Object context = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            if (!isSelected(info) || !(context instanceof Context)) return chain.proceed();
            return new RearWidgetHostView((Context) context);
        });
    }

    private boolean isSelected(@Nullable Object info) {
        return info instanceof AppWidgetProviderInfo && ((AppWidgetProviderInfo) info).provider != null
                && selected.contains(((AppWidgetProviderInfo) info).provider.flattenToString());
    }

    /**
     * 小部件展开用的 Context：同一块屏幕、同一主题，但不带 AppCompat 布局工厂
     * （背屏中心的 Activity 装了它，RemoteViews 展开时 TextView 会变成 AppCompatTextView，
     * 随后 setBackgroundResource 等调用被拒绝，整个小部件显示「无法添加微件」）。
     */
    @NonNull
    private static Context widgetContext(@NonNull Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app == null) return context;
            Context base = context.getDisplay() != null ? app.createDisplayContext(context.getDisplay()) : app;
            Context themed = new android.view.ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault);
            themed.getTheme().setTo(context.getTheme());
            return themed;
        } catch (Throwable error) {
            return context;
        }
    }
}
