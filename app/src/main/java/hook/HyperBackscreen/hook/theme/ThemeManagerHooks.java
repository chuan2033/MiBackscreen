package hook.HyperBackscreen.hook.theme;

import static hook.HyperBackscreen.hook.util.HookReflection.asString;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethodCompatible;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethodQuietly;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethodResolved;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethodResolvedByTarget;
import static hook.HyperBackscreen.hook.util.HookReflection.callNoArgMethodQuietly;
import static hook.HyperBackscreen.hook.util.HookReflection.callNoArgMethodResolved;
import static hook.HyperBackscreen.hook.util.HookReflection.callRearListGetter;
import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.findFirstClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findFirstDeclaredMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.findStaticCompanionField;
import static hook.HyperBackscreen.hook.util.HookReflection.getFieldValue;
import static hook.HyperBackscreen.hook.util.HookReflection.isEmpty;
import static hook.HyperBackscreen.hook.util.HookReflection.setFirstFieldValue;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.ModuleStrings;
import hook.HyperBackscreen.common.BackgroundTasks;
import hook.HyperBackscreen.hook.target.MethodTarget;
import hook.HyperBackscreen.hook.target.TargetMembers;
import hook.HyperBackscreen.hook.target.TargetResolver;
import hook.HyperBackscreen.hook.target.ThemeMethodTargets;
import hook.HyperBackscreen.hook.ModuleMain;
import hook.HyperBackscreen.hook.appindex.SubscreenAppIndex;

/**
 * 主题商店（{@code com.android.thememanager}）进程里的 Hook：背屏壁纸数量上限、设备身份与网络请求伪装、
 * 背屏资源下载的设备身份回退、背屏人脸录入兼容、AI 陪伴重复应用复用、背屏个性化预设补全、
 * 背屏设置页的模块入口与 AI 入口、设置页数据库与背屏选择同步、AI 应用卡页面生命周期（身份伪装开关）。
 *
 * <p>壁纸应用流程的资源修复在 {@link ThemeResourceRepair}，appInfo.json 同步在 {@link SubscreenAppIndex}。
 */
public final class ThemeManagerHooks {
    private final ModuleMain module;
    private final SubscreenAppIndex appIndex;
    private final ThemeResourceRepair resourceRepair;

    private final AtomicReference<Integer> pendingSelection = new AtomicReference<>();
    private final AtomicBoolean selectionSyncRunning = new AtomicBoolean();
    private volatile boolean themeAiAppPageVisible;
    /** 处于前台/已创建的 AI 应用卡链路页面实例；清空才代表离开该链路。 */
    private final Set<Object> themeAiAppFlowActivities =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final long THEME_DATABASE_SYNC_DEDUP_WINDOW_MS = 2_000L;
    private final Set<Object> themeSettingsShortcutControllers =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> themeAiShortcutControllers =
            Collections.newSetFromMap(new WeakHashMap<>());
    @Nullable
    private volatile Integer lastThemeDatabaseSyncedWidgetId;
    private volatile long lastThemeDatabaseSyncedAtElapsed;

    public ThemeManagerHooks(
            @NonNull ModuleMain module,
            @NonNull SubscreenAppIndex appIndex,
            @NonNull ThemeResourceRepair resourceRepair
    ) {
        this.module = module;
        this.appIndex = appIndex;
        this.resourceRepair = resourceRepair;
    }

    private static Intent moduleSettingsIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(
                Constants.MODULE_PACKAGE,
                Constants.MODULE_PACKAGE + ".ui.MainActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static Intent themeManagerIntent(@NonNull String className) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(Constants.THEME_STORE_PACKAGE, className));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public void installWallpaperLimitHook(@NonNull ClassLoader classLoader) {
        Class<?> viewModelClass = findClass(Constants.THEME_REAR_VIEWMODEL_CLASS, classLoader);
        if (viewModelClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Theme hook target missing: " + Constants.THEME_REAR_VIEWMODEL_CLASS);
            return;
        }
        Method applyCheckMethod = TargetMembers.uniqueMethod(viewModelClass, boolean.class, List.class);
        if (applyCheckMethod == null) {
            applyCheckMethod = findFirstDeclaredMethod(
                    viewModelClass,
                    new Class[]{List.class},
                    Constants.THEME_APPLY_CHECK_METHOD_18PRO,
                    Constants.THEME_APPLY_CHECK_METHOD_OS4,
                    Constants.THEME_APPLY_CHECK_METHOD);
        }
        if (applyCheckMethod == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Hook targets missing: " + Constants.THEME_REAR_VIEWMODEL_CLASS
                            + "#[" + Constants.THEME_APPLY_CHECK_METHOD_18PRO
                            + ", " + Constants.THEME_APPLY_CHECK_METHOD_OS4
                            + ", " + Constants.THEME_APPLY_CHECK_METHOD + "]");
            return;
        }
        module.hookMethodIfPresent(
                applyCheckMethod,
                Constants.THEME_REAR_VIEWMODEL_CLASS + "#" + applyCheckMethod.getName(),
                chain -> {
                    if (PrefsBridge.shouldRemoveWallpaperLimit(module)) {
                        return true;
                    }
                    return chain.proceed();
                }
        );
    }

    public void installThemeDeviceIdentityHooks(@NonNull ClassLoader classLoader) {
        // 只保留 isSupportRearScreen 标志。device / version / model 不在 DeviceUtils 层全局改写：
        // 那会让所有主题商店请求都声称自己是 18 Pro Max，普通背屏资源详情直接 404。
        // 需要伪装的请求由 installThemeNetworkDeviceSpoofHook 按路径与 request_flag 精确注入。
        hookBooleanTrueByTarget(
                classLoader,
                ThemeMethodTargets.DEVICE_UTILS_IS_SUPPORT_REAR);
        hookStringReturnByTarget(
                classLoader,
                ThemeMethodTargets.DEVICE_UTILS_DEVICE,
                Constants.THEME_REAR_DEVICE,
                this::shouldSpoofThemeAiPageDeviceIdentity);
        hookStringReturnByTarget(
                classLoader,
                ThemeMethodTargets.DEVICE_UTILS_MODEL,
                Constants.THEME_REAR_MODEL,
                this::shouldSpoofThemeAiPageDeviceIdentity);
        hookStringReturnByTarget(
                classLoader,
                ThemeMethodTargets.DEVICE_UTILS_VERSION,
                Constants.THEME_REAR_VERSION,
                this::shouldSpoofThemeAiPageDeviceIdentity);
        hookStringReturnByTarget(
                classLoader,
                ThemeMethodTargets.ONLINE_SERVICE_VERSION,
                Constants.THEME_REAR_VERSION,
                this::shouldSpoofThemeAiPageDeviceIdentity);
    }

    private boolean shouldSpoofThemeAiPageDeviceIdentity() {
        return themeAiAppPageVisible && PrefsBridge.shouldEnable18ProFeatures(module);
    }

    public void installThemeNetworkDeviceSpoofHook(@NonNull ClassLoader classLoader) {
        Class<?> interceptorClass = findClass(Constants.THEME_PARAM_INTERCEPTOR_CLASS, classLoader);
        Class<?> requestClass = findClass(Constants.THEME_NETWORK_REQUEST_CLASS, classLoader);
        if (interceptorClass == null || requestClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Theme network spoof target missing");
            return;
        }

        module.hookMethodIfPresent(
                findDeclaredMethod(
                        interceptorClass,
                        Constants.THEME_NETWORK_REWRITE_METHOD,
                        requestClass,
                        java.util.LinkedHashMap.class,
                        String.class),
                Constants.THEME_PARAM_INTERCEPTOR_CLASS + "#"
                        + Constants.THEME_NETWORK_REWRITE_METHOD,
                chain -> {
                    if (!PrefsBridge.shouldEnable18ProFeatures(module)) return chain.proceed();
                    Object request = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    Object params = chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null;
                    boolean diagnosticRequest = shouldLogThemeDiagnosticRequest(request);
                    boolean spoofed = false;
                    if (shouldSpoofThemeAiAppRequest(request) && params instanceof Map<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> map = (Map<String, String>) rawMap;
                        map.put("device", Constants.THEME_REAR_DEVICE);
                        map.put("model", Constants.THEME_REAR_MODEL);
                        map.put("version", Constants.THEME_REAR_VERSION);
                        map.put("isSupportRearScreen", "true");
                        spoofed = true;
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Spoofed Theme rear AI request as " + Constants.THEME_REAR_DEVICE);
                    }
                    if (diagnosticRequest) {
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Theme request diagnostic: spoofed=" + spoofed
                                        + ", " + summarizeThemeNetworkRequest(request, params));
                    }
                    return chain.proceed();
                }
        );
    }

    private boolean shouldLogThemeDiagnosticRequest(@Nullable Object request) {
        if (request == null) return false;
        return ThemeNetworkClassifier.shouldLogDiagnostic(themeRequestPathOrUrl(request));
    }

    @NonNull
    private String summarizeThemeNetworkRequest(@Nullable Object request, @Nullable Object params) {
        String path = asString(callMethodResolvedByTarget(request, ThemeMethodTargets.REQUEST_URL));
        String url = stripQueryAndFragment(String.valueOf(callMethodQuietly(request, "url")));
        String requestFlag = asString(callMethodQuietly(
                request, "s", Constants.THEME_AI_APP_REQUEST_FLAG_HEADER));
        return "path=" + path
                + ", url=" + clipForLog(url, 240)
                + ", request_flag=" + requestFlag
                + ", params=" + summarizeThemeNetworkParams(params);
    }

    @NonNull
    private String summarizeThemeNetworkParams(@Nullable Object params) {
        if (!(params instanceof Map<?, ?> map)) return String.valueOf(params);
        return "{device=" + map.get("device")
                + ", model=" + map.get("model")
                + ", version=" + map.get("version")
                + ", isSupportRearScreen=" + map.get("isSupportRearScreen")
                + ", category=" + map.get("category")
                + ", module=" + map.get("module")
                + ", screen=" + map.get("screen")
                + ", page=" + map.get("page")
                + ", request_flag=" + map.get("request_flag")
                + "}";
    }

    @NonNull
    private static String stripQueryAndFragment(@Nullable String value) {
        if (value == null || "null".equals(value)) return "null";
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return value.substring(0, end);
    }

    private boolean shouldSpoofThemeAiAppRequest(@Nullable Object request) {
        if (request == null) {
            return false;
        }
        String flag = themeRequestFlag(request);
        return ThemeNetworkClassifier.shouldSpoofDeviceIdentity(themeRequestPathOrUrl(request), flag);
    }

    @Nullable
    private String themeRequestPathOrUrl(@Nullable Object request) {
        String raw = asString(callMethodResolvedByTarget(request, ThemeMethodTargets.REQUEST_URL));
        if (!isEmpty(raw)) return raw;
        raw = String.valueOf(callMethodQuietly(request, "url"));
        return isEmpty(raw) ? null : raw;
    }

    @Nullable
    private String themeRequestFlag(@Nullable Object request) {
        return asString(callMethodResolved(request, String.class, "s", Constants.THEME_AI_APP_REQUEST_FLAG_HEADER));
    }

    public void installThemeLegacyDownloadHook(@NonNull ClassLoader classLoader) {
        Class<?> networkHelperClass = findClass(Constants.THEME_NETWORK_HELPER_CLASS, classLoader);
        Class<?> requestUrlClass = findClass(Constants.THEME_REQUEST_URL_CLASS, classLoader);
        if (networkHelperClass == null || requestUrlClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Theme legacy download hook target missing");
            return;
        }
        String downloadMethodName = TargetMembers.methodName(
                TargetMembers.resolveMethod(
                        networkHelperClass, String.class,
                        new Class<?>[]{requestUrlClass}, "f7l8"), "f7l8");
        module.hookMethodIfPresent(
                networkHelperClass,
                downloadMethodName,
                new Class[]{requestUrlClass},
                Constants.THEME_NETWORK_HELPER_CLASS + "#" + downloadMethodName,
                chain -> {
                    if (!PrefsBridge.shouldEnable18ProFeatures(module)) return chain.proceed();
                    Object requestUrl = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!isThemeRearDownloadRequest(requestUrl)) {
                        return chain.proceed();
                    }
                    // 背屏壁纸先按本机真实身份请求；AI 应用卡（非 rearscreen 类目）沿用 18 Pro Max 伪装。
                    boolean realFirst = isThemeRearDiffDownloadRequest(requestUrl)
                            || isThemeRearWallpaperDownloadRequest(requestUrl);
                    applyThemeRearDownloadIdentity(requestUrl, realFirst);
                    module.log(Log.DEBUG, Constants.LOG_TAG,
                            "Theme rear download request: "
                                    + summarizeThemeDownloadRequest(requestUrl));
                    Object result = chain.proceed();
                    module.log(Log.DEBUG, Constants.LOG_TAG,
                            "Theme rear download response length=" + themeResponseLength(result)
                                    + ", body=" + clipForLog(asString(result), 600));
                    // 首页商店会同时展示两个设备池的背屏资源：真实身份取不到说明该资源属于
                    // 18 Pro Max 池（如「Xiaomi 18 Pro 系列」），换 madrid 重试一次即可拿到。
                    if (realFirst && isThemeDownloadUrlError(result)) {
                        applyThemeRearDownloadIdentity(requestUrl, false);
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Theme rear download retry as 18 Pro Max: "
                                        + summarizeThemeDownloadRequest(requestUrl));
                        try {
                            Object retried = chain.proceed();
                            module.log(Log.DEBUG, Constants.LOG_TAG,
                                    "Theme rear download retry response length="
                                            + themeResponseLength(retried)
                                            + ", body=" + clipForLog(asString(retried), 600));
                            if (!isThemeDownloadUrlError(retried) || !(retried instanceof String)) {
                                result = retried;
                            }
                        } catch (Throwable throwable) {
                            module.log(Log.WARN, Constants.LOG_TAG,
                                    "Theme rear download retry failed: " + throwable);
                        }
                    }
                    return result;
                }
        );
    }

    private static int themeResponseLength(@Nullable Object result) {
        String body = result instanceof String ? (String) result : null;
        return body == null ? -1 : body.length();
    }

    private static boolean isThemeDownloadUrlError(@Nullable Object result) {
        return result instanceof String body
                && body.contains(Constants.THEME_DOWNLOAD_ERROR_MARKER);
    }

    private boolean isThemeRearDownloadRequest(@Nullable Object requestUrl) {
        Object baseUrl = callMethodQuietly(requestUrl, "getBaseUrl");
        return String.valueOf(baseUrl).contains(Constants.THEME_DOWNLOAD_PATH);
    }

    /**
     * 把设备身份写入背屏资源下载请求。
     *
     * <p>主题商店首页会同时展示两个设备池的背屏资源，而 {@code download/v2} 接口按 {@code device}
     * 分池发放，任选其一都会让另一池取不到：
     * <ul>
     *   <li>本机真实身份（{@code ro.product.device}）：17 系列背屏壁纸、背屏个性化、用户分享等；</li>
     *   <li>18 Pro Max（{@code madrid}）：18 Pro 系列专属背屏、AI 应用卡资源。</li>
     * </ul>
     * 因此调用方先按资源倾向选一个身份发出请求，失败时再由 {@code installThemeLegacyDownloadHook}
     * 换另一个身份重试（见该方法内的回退分支）。
     *
     * @param realIdentity true = 本机真实 device/version；false = 18 Pro Max 伪装
     */
    private void applyThemeRearDownloadIdentity(
            @Nullable Object requestUrl, boolean realIdentity) {
        String baseUrl = asString(callMethodQuietly(requestUrl, "getBaseUrl"));
        if (!isEmpty(baseUrl)) {
            String device = realIdentity ? android.os.Build.DEVICE : Constants.THEME_REAR_DEVICE;
            String version = realIdentity
                    ? getCurrentThemeSystemVersion() : Constants.THEME_REAR_VERSION;
            String rewritten = rewriteUrlParameter(baseUrl, "device", device);
            if (!isEmpty(version)) {
                rewritten = rewriteUrlParameter(rewritten, "version", version);
            }
            if (!rewritten.equals(baseUrl)) {
                callMethodQuietly(requestUrl, "setBaseUrl", rewritten);
            }
        }
        Object existing = callMethodQuietly(
                requestUrl, "getParameter", "isSupportRearScreen");
        if (existing != null && "true".equalsIgnoreCase(String.valueOf(existing))) {
            return;
        }
        callMethodQuietly(requestUrl, "addParameter", "isSupportRearScreen", "true");
    }

    /**
     * 判定是否为背屏壁纸类目下载。仅认 {@code category} 精确等于 {@code rearscreen}，
     * 避免误伤带 rearscreen 前缀的 AI 应用卡类目。
     *
     * <p>注意：{@code category} 只存在于 baseUrl 的 query 里，{@code RequestUrl#getParameter}
     * 读不到（它只反映 addParameter 写入的参数容器），必须直接解析 URL。
     */
    private boolean isThemeRearWallpaperDownloadRequest(@Nullable Object requestUrl) {
        String baseUrl = asString(callMethodQuietly(requestUrl, "getBaseUrl"));
        return Constants.THEME_REAR_WALLPAPER_CATEGORY
                .equalsIgnoreCase(readUrlParameter(baseUrl, "category"));
    }

    /** 从 URL 的 query string 中读取参数值；不存在时返回空串。 */
    @NonNull
    private static String readUrlParameter(@Nullable String url, @NonNull String key) {
        if (isEmpty(url)) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[?&]" + java.util.regex.Pattern.quote(key) + "=([^&#]*)")
                .matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    @NonNull
    private static String getCurrentThemeSystemVersion() {
        String release = android.os.Build.VERSION.RELEASE;
        String incremental = android.os.Build.VERSION.INCREMENTAL;
        if (release == null || release.isEmpty() || incremental == null || incremental.isEmpty()) {
            return "";
        }
        return release + "_" + incremental;
    }

    private boolean isThemeRearDiffDownloadRequest(@Nullable Object requestUrl) {
        Object baseUrl = callMethodQuietly(requestUrl, "getBaseUrl");
        return String.valueOf(baseUrl).contains("/diff" + Constants.THEME_DOWNLOAD_PATH);
    }

    @NonNull
    private static String rewriteUrlParameter(
            @NonNull String url, @NonNull String key, @NonNull String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([?&])" + key + "=[^&#]*")
                .matcher(url);
        if (matcher.find()) {
            return matcher.replaceFirst("$1" + key + "=" + value);
        }
        return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
    }

    /** 诊断用。device/version/category 一律从 baseUrl 读——getParameter 读不到它们。 */
    @NonNull
    private String summarizeThemeDownloadRequest(@Nullable Object requestUrl) {
        String baseUrl = String.valueOf(callMethodQuietly(requestUrl, "getBaseUrl"));
        return "baseUrl=" + baseUrl
                + ", category=" + readUrlParameter(baseUrl, "category")
                + ", urlDevice=" + readUrlParameter(baseUrl, "device")
                + ", urlVersion=" + readUrlParameter(baseUrl, "version")
                + ", diff=" + isThemeRearDiffDownloadRequest(requestUrl)
                + ", isSupportRearScreen="
                + String.valueOf(callMethodQuietly(requestUrl, "getParameter", "isSupportRearScreen"));
    }

    @NonNull
    private static String clipForLog(@Nullable String value, int maxChars) {
        if (value == null) return "null";
        if (value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "...";
    }

    public void installThemeFaceEnrollmentHook(@NonNull ClassLoader classLoader) {
        // Theme Manager 11.5.3.1: k(Context) is the enrollment-count query only.
        // Keep the credential gate, enrollment UI, content audit and authentication intact.
        Class<?> helper = findClass("com.rearScreen.helper.RearScreenBiometricHelper", classLoader);
        if (helper == null) {
            // 原来是静默返回：类名随主题商店版本漂移时完全查不到痕迹，是诊断盲区。
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Face enrollment hook target missing:"
                            + " com.rearScreen.helper.RearScreenBiometricHelper");
            return;
        }
        Method method = TargetMembers.resolveMethod(
                helper, int.class, new Class<?>[]{Context.class}, "k");
        if (method == null || method.getReturnType() != int.class
                || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Face enrollment hook signature mismatch: k(android.content.Context):int");
            return;
        }
        boolean installed = module.hookMethodIfPresent(method, "RearScreenBiometricHelper#k", chain -> {
            Object original = chain.proceed();
            if (!(original instanceof Integer count) || count > 0
                    || !PrefsBridge.shouldFixFaceEnrollment(module)) return original;
            if (chain.getArgs().isEmpty() || !(chain.getArgs().get(0) instanceof Context context)) {
                return original;
            }
            int actual = ThemeFaceEnrollment.read(context);
            return actual >= 0 ? actual : original;
        });
        module.log(installed ? Log.INFO : Log.WARN, Constants.LOG_TAG,
                installed ? "Face enrollment compatibility hook installed"
                        : "Face enrollment compatibility hook not installed");
    }

    /**
     * 背屏 AI 陪伴壁纸的重复应用。
     *
     * 详情页入口以 {@code isUpdate = false} 进入应用流程，宿主只在该标志为 true 时按
     * (resId, applyId) 命中已有项并替换，否则按新 applyId 新增一行，「妙享背屏」里于是出现
     * 多条同 resId 的记录。这里在 AI 陪伴场景把 applyId 对齐到已有项并改走替换分支；
     * 普通壁纸保持宿主原行为，仍允许同一 resId 存在多个不同编辑版本。
     */
    public void installAiCompanionReuseApplyHook(@NonNull ClassLoader classLoader) {
        Class<?> companionClass = findClass(
                Constants.THEME_REAR_RES_OPERATION_COMPANION_CLASS, classLoader);
        Class<?> itemClass = findClass(Constants.THEME_REAR_LIST_ITEM_CLASS, classLoader);
        if (companionClass == null || itemClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "AI companion apply reuse target missing: "
                            + Constants.THEME_REAR_RES_OPERATION_COMPANION_CLASS);
            return;
        }

        Method applyEntry = findAiCompanionApplyEntry(companionClass, itemClass);
        if (applyEntry == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "AI companion apply entry not found");
            return;
        }

        module.hookMethodIfPresent(
                applyEntry,
                Constants.THEME_REAR_RES_OPERATION_COMPANION_CLASS + "#" + applyEntry.getName(),
                chain -> {
                    if (!PrefsBridge.shouldFixRearScreenApply(module)) {
                        return chain.proceed();
                    }
                    List<Object> args = chain.getArgs();
                    if (args.size() < 2) {
                        return chain.proceed();
                    }
                    Object isUpdate = args.get(0);
                    Object bean = args.get(1);
                    if (!(isUpdate instanceof Boolean) || Boolean.TRUE.equals(isUpdate)) {
                        return chain.proceed();
                    }
                    if (bean == null || !isAiCompanionItem(bean)) {
                        return chain.proceed();
                    }

                    String currentApplyId = asString(callMethodQuietly(bean, "getApplyId"));
                    String reusableApplyId = findReusedAiCompanionApplyId(bean, classLoader);
                    if (reusableApplyId == null || reusableApplyId.equals(currentApplyId)) {
                        return chain.proceed();
                    }
                    if (!alignAiCompanionApplyId(bean, currentApplyId, reusableApplyId)) {
                        return chain.proceed();
                    }

                    List<Object> rewritten = new ArrayList<>(args);
                    rewritten.set(0, Boolean.TRUE);
                    module.log(Log.INFO, Constants.LOG_TAG,
                            "AI companion apply reused existing id: resId="
                                    + asString(callMethodQuietly(bean, "getResId"))
                                    + ", " + currentApplyId + " -> " + reusableApplyId);
                    return chain.proceed(rewritten.toArray());
                }
        );
    }

    @Nullable
    private static Method findAiCompanionApplyEntry(
            @NonNull Class<?> companionClass, @NonNull Class<?> itemClass) {
        Method signatureMatch = null;
        for (Method method : companionClass.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length < 3
                    || parameterTypes[0] != boolean.class
                    || parameterTypes[1] != itemClass) {
                continue;
            }
            if (Constants.THEME_REAR_APPLY_ENTRY_METHOD.equals(method.getName())) {
                return method;
            }
            if (signatureMatch == null) {
                signatureMatch = method;
            }
        }
        return signatureMatch;
    }

    private boolean isAiCompanionItem(@NonNull Object bean) {
        if (Boolean.TRUE.equals(callMethodQuietly(bean, "isNFC"))) {
            return false;
        }
        String resType = asString(callMethodQuietly(bean, "getResType"));
        String resSubType = asString(callMethodQuietly(bean, "getResSubType"));
        return Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(resSubType)
                || Constants.THEME_REAR_AI_COMPANION_SUB_TYPE.equals(resSubType);
    }

    /**
     * 取该 resId 已应用过的最近一条 applyId。宿主落库主键是 (resId, applyId)，
     * 对齐后同 resId 不会再多出一行。
     */
    @Nullable
    private String findReusedAiCompanionApplyId(
            @NonNull Object bean, @NonNull ClassLoader classLoader) {
        try {
            String resId = asString(callMethod(bean, "getResId"));
            if (isEmpty(resId)) {
                return null;
            }
            Class<?> managerClass = findClass(
                    Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) {
                return null;
            }
            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) {
                return null;
            }
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            if (companion == null) {
                return null;
            }
            Object manager = callMethod(
                    companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD);
            List<?> items = callRearListGetter(manager);
            if (items == null) {
                return null;
            }

            String currentApplyId = asString(callMethodQuietly(bean, "getApplyId"));
            String latestApplyId = null;
            long latestValue = Long.MIN_VALUE;
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                if (!resId.equals(asString(callMethodQuietly(item, "getResId")))) {
                    continue;
                }
                String applyId = asString(callMethodQuietly(item, "getApplyId"));
                if (isEmpty(applyId) || applyId.equals(currentApplyId)) {
                    continue;
                }
                long value = parseApplyIdValue(applyId);
                if (value > latestValue) {
                    latestValue = value;
                    latestApplyId = applyId;
                }
            }
            return latestApplyId;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "Find AI companion applyId failed", e);
            return null;
        }
    }

    /**
     * 把 bean 上的 applyId 换成已有值。运行时路径由 applyId 实时拼出，但 mamlEditConfigPath
     * 一类的字段是详情页带过来的绝对路径，需要同步改写，否则仍会落在新 applyId 的目录上。
     */
    private boolean alignAiCompanionApplyId(
            @NonNull Object bean, @NonNull String fromApplyId, @NonNull String toApplyId) {
        try {
            if (!isEmpty(fromApplyId)) {
                rewriteApplyIdPath(bean, "getMamlEditConfigPath", "setMamlEditConfigPath",
                        fromApplyId, toApplyId);
                rewriteApplyIdPath(bean, "getSnapshotPreviewPath", "setSnapshotPreviewPath",
                        fromApplyId, toApplyId);
                rewriteApplyIdPath(bean, "getResSnapshotPath", "setResSnapshotPath",
                        fromApplyId, toApplyId);
                rewriteApplyIdPath(bean, "getMetaSnapshotPath", "setMetaSnapshotPath",
                        fromApplyId, toApplyId);
            }
            callMethod(bean, "setApplyId", toApplyId);
            return true;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "Align AI companion applyId failed", e);
            return false;
        }
    }

    private void rewriteApplyIdPath(
            @NonNull Object bean,
            @NonNull String getter,
            @NonNull String setter,
            @NonNull String fromApplyId,
            @NonNull String toApplyId) throws Throwable {
        String value = asString(callMethodQuietly(bean, getter));
        if (isEmpty(value) || !value.contains(fromApplyId)) {
            return;
        }
        callMethod(bean, setter, value.replace(fromApplyId, toApplyId));
    }

    private static long parseApplyIdValue(@NonNull String applyId) {
        try {
            return Long.parseLong(applyId);
        } catch (NumberFormatException e) {
            return applyId.hashCode();
        }
    }

    public void installRearScreenPersonalizationPresetHook(@NonNull ClassLoader classLoader) {
        Class<?> settingHelperCompanionClass = findClass(
                Constants.THEME_SETTING_HELPER_COMPANION_CLASS, classLoader);
        if (settingHelperCompanionClass != null) {
            Method cmfMethod = TargetResolver.resolveMethod(
                    classLoader, ThemeMethodTargets.SETTING_HELPER_CMF);
            String cmfLabel = Constants.THEME_SETTING_HELPER_COMPANION_CLASS
                    + "#" + (cmfMethod != null ? cmfMethod.getName() : "zy");
            module.hookMethodIfPresent(
                    cmfMethod != null ? cmfMethod
                            : findDeclaredMethod(settingHelperCompanionClass, "zy"),
                    cmfLabel,
                    chain -> {
                        Object result = chain.proceed();
                        if (!PrefsBridge.shouldEnable18ProFeatures(module)) return result;
                        String cmf = asString(result);
                        return isEmpty(cmf) ? Constants.THEME_REAR_CMF_FALLBACK : result;
                    }
            );
        } else {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization CMF hook target missing: "
                            + Constants.THEME_SETTING_HELPER_COMPANION_CLASS);
        }

        Class<?> miuiUtilsClass = findClass(Constants.THEME_MIUI_UTILS_CLASS, classLoader);
        if (miuiUtilsClass != null) {
            Method isRearMethod = TargetResolver.resolveMethod(
                    classLoader, ThemeMethodTargets.MIUI_UTILS_IS_REAR);
            String isRearLabel = Constants.THEME_MIUI_UTILS_CLASS
                    + "#" + (isRearMethod != null ? isRearMethod.getName() : "zurt");
            module.hookMethodIfPresent(
                    isRearMethod != null ? isRearMethod
                            : findDeclaredMethod(miuiUtilsClass, "zurt"),
                    isRearLabel,
                    chain -> PrefsBridge.shouldEnable18ProFeatures(module) ? false : chain.proceed()
            );
        } else {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization version hook target missing: "
                            + Constants.THEME_MIUI_UTILS_CLASS);
        }

        Class<?> repositoryClass = findClass(
                Constants.THEME_REAR_PRESET_REPOSITORY_CLASS, classLoader);
        Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
        if (repositoryClass == null || continuationClass == null) {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization preset hook target missing: "
                            + Constants.THEME_REAR_PRESET_REPOSITORY_CLASS);
            return;
        }
        Method presetMethod = TargetResolver.resolveMethod(
                classLoader, ThemeMethodTargets.PRESET_REPOSITORY_LIST);
        String presetMethodName = TargetMembers.methodName(presetMethod, "k");
        module.hookMethodIfPresent(
                presetMethod != null ? presetMethod
                        : findDeclaredMethod(repositoryClass, presetMethodName, new Class[]{continuationClass}),
                Constants.THEME_REAR_PRESET_REPOSITORY_CLASS + "#" + presetMethodName,
                chain -> {
                    Object result = chain.proceed();
                    if (!PrefsBridge.shouldEnable18ProFeatures(module)) return result;
                    return ensureRearPersonalizationAiPresets(result, chain.getThisObject());
                }
        );
    }

    private Object ensureRearPersonalizationAiPresets(
            @Nullable Object result,
            @Nullable Object repository
    ) {
        if (!(result instanceof List<?> rawList)) return result;
        if (hasRearPersonalizationAiPresets(rawList)) return result;

        ArrayList<Object> merged = new ArrayList<>(rawList);
        int added = 0;
        added += mergeRearPersonalizationPresetPath(
                merged, repository, Constants.THEME_REAR_TEMPLATE_NORMAL_DATA_DIR);
        added += mergeRearPersonalizationPresetPath(
                merged, repository, Constants.THEME_REAR_TEMPLATE_NORMAL_PRODUCT_DIR);
        added += mergeRearPersonalizationPresetPath(
                merged, repository, Constants.THEME_REAR_TEMPLATE_NORMAL_SYSTEM_DIR);
        if (added > 0) {
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Rear personalization AI presets ensured: added=" + added);
            return merged;
        }
        module.log(Log.DEBUG, Constants.LOG_TAG,
                "Rear personalization AI preset source unavailable");
        return result;
    }

    private int mergeRearPersonalizationPresetPath(
            @NonNull ArrayList<Object> target,
            @Nullable Object repository,
            @NonNull String path
    ) {
        if (repository == null) return 0;
        ArrayList<Object> supplemental = new ArrayList<>();
        try {
            String mergeMethodName = TargetMembers.methodName(
                    TargetMembers.resolveMethod(
                            repository.getClass(), List.class,
                            new Class<?>[]{ArrayList.class, String.class}, "zurt"), "zurt");
            Object value = callMethodCompatible(repository, mergeMethodName, supplemental, path);
            List<?> parsed = value instanceof List<?> list ? list : supplemental;
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization preset source: path=" + path
                            + ", size=" + parsed.size()
                            + ", current=" + target.size());
            int added = mergeRearPersonalizationPresetCategories(target, parsed);
            if (added == 0) {
                added = mergeLeadingRearPersonalizationPresetCategories(target, parsed);
            }
            return added;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Rear personalization preset source failed: path=" + path, e);
            return 0;
        }
    }

    private int mergeRearPersonalizationPresetCategories(
            @NonNull ArrayList<Object> target,
            @NonNull List<?> supplemental
    ) {
        int added = 0;
        for (Object category : supplemental) {
            String categoryType = asString(callNoArgMethodQuietly(category, "getCategoryType"));
            if (!isRearPersonalizationAiCategory(categoryType)) continue;
            Object existing = findRearPresetCategory(target, categoryType);
            if (existing == null) {
                target.add(category);
                added++;
                continue;
            }
            Object existingItems = callNoArgMethodQuietly(existing, "getItemList");
            Object supplementalItems = callNoArgMethodQuietly(category, "getItemList");
            if (!(existingItems instanceof List<?> current)
                    || !(supplementalItems instanceof List<?> extras)) {
                continue;
            }
            ArrayList<Object> toAdd = new ArrayList<>();
            for (Object item : extras) {
                if (isRearPersonalizationAiItem(item)
                        && !containsRearPresetItem(current, item)
                        && !containsRearPresetItem(toAdd, item)) {
                    toAdd.add(item);
                }
            }
            if (toAdd.isEmpty()) continue;
            // 一次性 addAll：宿主列表若是不可变 List，会在改动前就抛异常，不会留下改了一半的状态；
            // 这时换成副本，通过 setter 写回。
            try {
                @SuppressWarnings("unchecked")
                List<Object> mutableCurrent = (List<Object>) current;
                mutableCurrent.addAll(toAdd);
                added += toAdd.size();
            } catch (UnsupportedOperationException immutable) {
                ArrayList<Object> copy = new ArrayList<>(current);
                copy.addAll(toAdd);
                if (setListProperty(existing, "setItemList", copy)) {
                    added += toAdd.size();
                }
            }
        }
        return added;
    }

    private int mergeLeadingRearPersonalizationPresetCategories(
            @NonNull ArrayList<Object> target,
            @NonNull List<?> supplemental
    ) {
        int missing = supplemental.size() - target.size();
        if (missing <= 0) return 0;
        // 按 categoryType 去重：目标里已有的分类不再从补充来源插一份。
        ArrayList<Object> leading = new ArrayList<>();
        for (Object category : supplemental) {
            if (leading.size() >= missing) break;
            String type = asString(callNoArgMethodQuietly(category, "getCategoryType"));
            if (type != null && findRearPresetCategory(target, type) != null) continue;
            leading.add(category);
        }
        int limit = leading.size();
        if (limit == 0) return 0;
        target.addAll(0, leading);
        module.log(Log.INFO, Constants.LOG_TAG,
                "Rear personalization preset leading categories restored: added=" + limit
                        + ", source=" + supplemental.size()
                        + ", result=" + target.size());
        return limit;
    }

    private static boolean setListProperty(@NonNull Object target, @NonNull String setter, @NonNull List<?> value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(setter) || m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(value.getClass())) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(target, value);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    @Nullable
    private Object findRearPresetCategory(
            @NonNull List<?> categories,
            @Nullable String categoryType
    ) {
        for (Object category : categories) {
            if (categoryType != null
                    && categoryType.equals(asString(
                    callNoArgMethodQuietly(category, "getCategoryType")))) {
                return category;
            }
        }
        return null;
    }

    private boolean hasRearPersonalizationAiPresets(@NonNull List<?> categories) {
        boolean hasAiCompanion = false;
        boolean hasGroupPhoto = false;
        boolean hasPhoneCase = false;
        for (Object category : categories) {
            String categoryType = asString(callNoArgMethodQuietly(category, "getCategoryType"));
            if (Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(categoryType)) {
                hasAiCompanion = true;
            }
            Object items = callNoArgMethodQuietly(category, "getItemList");
            if (!(items instanceof List<?> list)) continue;
            for (Object item : list) {
                String resType = asString(callNoArgMethodQuietly(item, "getResType"));
                if (Constants.THEME_REAR_GROUP_PHOTO_RES_TYPE.equals(resType)) {
                    hasGroupPhoto = true;
                } else if (Constants.THEME_REAR_PHONE_CASE_RES_TYPE.equals(resType)) {
                    hasPhoneCase = true;
                }
            }
        }
        return hasAiCompanion && hasGroupPhoto && hasPhoneCase;
    }

    private int countRearPersonalizationPresetItems(@NonNull List<?> categories) {
        int count = 0;
        for (Object category : categories) {
            if (isRearPersonalizationAiCategory(asString(
                    callNoArgMethodQuietly(category, "getCategoryType")))) {
                count++;
            }
            Object items = callNoArgMethodQuietly(category, "getItemList");
            if (!(items instanceof List<?> list)) continue;
            for (Object item : list) {
                if (isRearPersonalizationAiItem(item)) count++;
            }
        }
        return count;
    }

    private boolean isRearPersonalizationAiCategory(@Nullable String categoryType) {
        return Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(categoryType)
                || Constants.THEME_REAR_AI_WALLPAPER_CATEGORY_TYPE.equals(categoryType);
    }

    private boolean isRearPersonalizationAiItem(@Nullable Object item) {
        String resType = asString(callNoArgMethodQuietly(item, "getResType"));
        return Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_GROUP_PHOTO_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_PHONE_CASE_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_AI_WALLPAPER_CATEGORY_TYPE.equals(resType);
    }

    private boolean containsRearPresetItem(
            @NonNull List<?> items,
            @Nullable Object targetItem
    ) {
        String targetKey = rearPresetItemKey(targetItem);
        for (Object item : items) {
            if (targetKey.equals(rearPresetItemKey(item))) return true;
        }
        return false;
    }

    @NonNull
    private String rearPresetItemKey(@Nullable Object item) {
        String resType = asString(callNoArgMethodQuietly(item, "getResType"));
        String resId = asString(callNoArgMethodQuietly(item, "getResId"));
        String resName = asString(callNoArgMethodQuietly(item, "getResName"));
        return (resType == null ? "" : resType)
                + ":"
                + (!isEmpty(resId) ? resId : (resName == null ? "" : resName));
    }

    public void installThemeSettingsShortcutHook(@NonNull ClassLoader classLoader) {
        Class<?> entryConfigCompanionClass = findClass(
                Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS, classLoader);
        if (entryConfigCompanionClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings entry config target missing: "
                            + Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS);
            return;
        }

        String entryConfigMethodName = TargetMembers.methodName(
                TargetMembers.resolveMethod(
                        entryConfigCompanionClass, List.class,
                        new Class<?>[]{Context.class}, "k"), "k");
        module.hookMethodIfPresent(
                entryConfigCompanionClass,
                entryConfigMethodName,
                new Class[]{Context.class},
                Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS + "#" + entryConfigMethodName,
                chain -> {
                    Object result = chain.proceed();
                    boolean showModuleShortcut = PrefsBridge.shouldShowThemeSettingsShortcut(module);
                    Intent aiIntent = PrefsBridge.shouldEnable18ProFeatures(module)
                            ? resolveRearScreenAiIntent(classLoader) : null;
                    if (!showModuleShortcut && aiIntent == null) {
                        return result;
                    }
                    Object context = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (context instanceof Context) {
                        return injectThemeSettingsShortcutList(
                                result, (Context) context, classLoader, showModuleShortcut, aiIntent);
                    }
                    return result;
                }
        );

        Class<?> adapterClass = findClass(Constants.THEME_REAR_SETTING_ADAPTER_CLASS, classLoader);
        Class<?> viewHolderClass = findClass(
                "androidx.recyclerview.widget.RecyclerView$ViewHolder", classLoader);
        if (adapterClass == null || viewHolderClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings adapter target missing");
            return;
        }
        module.hookMethodIfPresent(
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
                    } else if (data != null && position >= 0 && position < data.size()
                            && themeAiShortcutControllers.contains(data.get(position))) {
                        Object holder = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        bindThemeAiShortcutRow(holder);
                    } else {
                        // 普通设置项复用了之前被我们改过的行：把原来的点击监听和图标还回去。
                        Object holder = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        restoreThemeSettingsRow(holder);
                    }
                    return result;
                }
        );
    }

    public void installThemeSettingsAiVisibilityHooks(@NonNull ClassLoader classLoader) {
        hookBooleanTrueIfPresent(
                classLoader,
                Constants.THEME_REAR_SCREEN_SETTING_MODULE_COMPANION_CLASS,
                "k");
        hookBooleanTrueIfPresent(
                classLoader,
                Constants.THEME_REAR_SCREEN_AI_APP_CONTROLLER_CLASS,
                "k");
    }

    private void hookBooleanTrueIfPresent(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String methodName
    ) {
        Class<?> targetClass = findClass(className, classLoader);
        if (targetClass == null) {
            module.log(Log.DEBUG, Constants.LOG_TAG, "AI entry hook target missing: " + className);
            return;
        }
        module.hookBooleanTrueIfPresent(targetClass, className, methodName);
    }

    private void hookStringReturnIfPresent(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String methodName,
            @NonNull String value
    ) {
        hookStringReturnIfPresent(
                classLoader,
                className,
                methodName,
                value,
                () -> PrefsBridge.shouldEnable18ProFeatures(module));
    }

    private void hookStringReturnIfPresent(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String methodName,
            @NonNull String value,
            @NonNull BooleanSupplier enabled
    ) {
        Class<?> targetClass = findClass(className, classLoader);
        if (targetClass == null) {
            module.log(Log.DEBUG, Constants.LOG_TAG, "String hook target missing: " + className);
            return;
        }
        String resolved = TargetMembers.methodName(
                TargetMembers.resolveNoArgMethod(targetClass, String.class, methodName),
                methodName);
        module.hookMethodIfPresent(
                targetClass,
                resolved,
                new Class[]{},
                className + "#" + resolved,
                chain -> enabled.getAsBoolean() ? value : chain.proceed()
        );
    }

    /** 用 DexKit 方法体锚点定位方法，命中后挂钩子把返回值替换为固定字符串。 */
    private void hookStringReturnByTarget(
            @NonNull ClassLoader classLoader,
            @NonNull MethodTarget target,
            @NonNull String value,
            @NonNull BooleanSupplier enabled
    ) {
        Method method = TargetResolver.resolveMethod(classLoader, target);
        if (method == null) {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "String hook target missing: " + target.getOwnerClass() + "#" + target.getFallbackName());
            return;
        }
        module.hookMethodIfPresent(
                method,
                target.getOwnerClass() + "#" + method.getName(),
                chain -> enabled.getAsBoolean() ? value : chain.proceed()
        );
    }

    /** 用 DexKit 方法体锚点定位无参布尔方法，命中后挂钩子在启用时强制返回 true。 */
    private void hookBooleanTrueByTarget(
            @NonNull ClassLoader classLoader,
            @NonNull MethodTarget target
    ) {
        Method method = TargetResolver.resolveMethod(classLoader, target);
        if (method == null) {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "Boolean hook target missing: " + target.getOwnerClass() + "#" + target.getFallbackName());
            return;
        }
        module.hookMethodIfPresent(
                method,
                target.getOwnerClass() + "#" + method.getName(),
                chain -> PrefsBridge.shouldEnable18ProFeatures(module) ? true : chain.proceed()
        );
    }

    private Object injectThemeSettingsShortcutList(
            @Nullable Object result,
            @NonNull Context context,
            @NonNull ClassLoader classLoader,
            boolean showModuleShortcut,
            @Nullable Intent aiIntent
    ) {
        if (!(result instanceof List<?> rawList)) return result;
        boolean hasModuleShortcut = containsTrackedController(rawList, themeSettingsShortcutControllers);
        boolean hasAiShortcut = containsTrackedController(rawList, themeAiShortcutControllers)
                || containsControllerKey(rawList, "ai_app_entry");
        boolean shouldInsertModule = showModuleShortcut && !hasModuleShortcut;
        boolean shouldInsertAi = aiIntent != null && !hasAiShortcut;
        if (!shouldInsertModule && !shouldInsertAi) return result;

        List<String> keys = new ArrayList<>();
        for (Object item : rawList) {
            Object key = callNoArgMethodResolved(item, String.class, "g");
            // 与 rawList 一一对齐（取不到 key 的条目占 null 位），算出的下标才能直接用于 rawList。
            keys.add(key instanceof String ? (String) key : null);
        }
        List<Integer> insertionIndexes = SettingsEntryPlacement.insertionIndexesAfterAnchor(
                keys, (shouldInsertAi ? 1 : 0) + (shouldInsertModule ? 1 : 0));
        if (insertionIndexes.isEmpty()) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings shortcut anchor missing in rear screen page");
            return result;
        }

        ArrayList<Object> copy = new ArrayList<>(rawList);
        int nextSlot = 0;
        if (shouldInsertAi) {
            Object shortcut = createThemeSettingsShortcutController(
                    context, classLoader, ModuleStrings.get(context, "ai_rear_screen_title", "AI陪伴", "AI Companion"), aiIntent);
            if (shortcut != null) {
                copy.add(Math.min(insertionIndexes.get(nextSlot++), copy.size()), shortcut);
                themeAiShortcutControllers.add(shortcut);
                module.log(Log.INFO, Constants.LOG_TAG, "AI rear screen entry inserted");
            }
        }
        if (shouldInsertModule) {
            Object shortcut = createThemeSettingsShortcutController(
                    context, classLoader, "MiBackscreen", moduleSettingsIntent());
            if (shortcut != null) {
                int insertionIndex = insertionIndexes.get(nextSlot);
                copy.add(Math.min(insertionIndex, copy.size()), shortcut);
                themeSettingsShortcutControllers.add(shortcut);
                module.log(Log.INFO, Constants.LOG_TAG,
                        "MiBackscreen theme settings entry inserted after "
                                + keys.get(insertionIndexes.get(0) - 1));
            }
        }
        return copy;
    }

    private boolean containsTrackedController(
            @NonNull List<?> list,
            @NonNull Set<Object> trackedControllers
    ) {
        for (Object item : list) {
            if (trackedControllers.contains(item)) return true;
        }
        return false;
    }

    private boolean containsControllerKey(@NonNull List<?> list, @NonNull String expectedKey) {
        for (Object item : list) {
            Object key = callNoArgMethodResolved(item, String.class, "g");
            if (expectedKey.equals(key)) return true;
        }
        return false;
    }

    @Nullable
    private Object createThemeSettingsShortcutController(
            @NonNull Context context,
            @NonNull ClassLoader classLoader,
            @NonNull String title,
            @NonNull Intent intent
    ) {
        try {
            Class<?> controllerClass = findClass(
                    Constants.THEME_USER_GUIDE_CONTROLLER_CLASS, classLoader);
            if (controllerClass == null) return null;

            Constructor<?> constructor = controllerClass.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            Object controller = constructor.newInstance(context);
            boolean fieldsReady = setFirstFieldValue(
                    controller,
                    new String[]{
                            Constants.THEME_BASE_CONTROLLER_TITLE_FIELD_18PRO,
                            Constants.THEME_BASE_CONTROLLER_TITLE_FIELD
                    },
                    title)
                    && setFirstFieldValue(
                    controller,
                    new String[]{
                            Constants.THEME_USER_GUIDE_INTENT_FIELD_18PRO,
                            Constants.THEME_USER_GUIDE_INTENT_FIELD
                    },
                    intent);
            if (!fieldsReady) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "Theme settings shortcut fields missing: "
                                + Constants.THEME_USER_GUIDE_CONTROLLER_CLASS);
                return null;
            }
            return controller;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "createThemeSettingsShortcutController failed", e);
            return null;
        }
    }

    @Nullable
    private Intent resolveRearScreenAiIntent(@NonNull ClassLoader classLoader) {
        Class<?> targetClass = findFirstClass(
                classLoader,
                Constants.THEME_REAR_SCREEN_AI_APP_ACTIVITY,
                Constants.THEME_AI_REAR_SCREEN_LIST_ACTIVITY);
        return targetClass == null ? null : themeManagerIntent(targetClass.getName());
    }

    @Nullable
    private List<?> getThemeSettingsAdapterData(@Nullable Object adapter) {
        Object data = getFieldValue(adapter, Constants.THEME_REAR_SETTING_ADAPTER_DATA_FIELD_18PRO);
        if (!isThemeSettingsControllerList(data)) {
            data = getFieldValue(adapter, Constants.THEME_REAR_SETTING_ADAPTER_DATA_FIELD);
        }
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
            Object key = callNoArgMethodResolved(item, String.class, "g");
            if ("user_guide".equals(key) || "serve_assistant".equals(key)) {
                hasKnownRearScreenEntry = true;
            }
        }
        return hasKnownRearScreenEntry;
    }

    /** 被模块入口借用过的 RecyclerView 行 → 借用前的点击监听与图标。 */
    private final Map<View, OriginalSettingsRow> borrowedSettingsRows =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class OriginalSettingsRow {
        @Nullable final View.OnClickListener listener;
        @Nullable final ImageView iconView;
        @Nullable final Drawable icon;
        /** 我们设上去的监听；还原时只有当前监听仍是它才替换，宿主每次 bind 自己重设的就不动。 */
        @Nullable View.OnClickListener borrowed;

        OriginalSettingsRow(@Nullable View.OnClickListener listener,
                            @Nullable ImageView iconView, @Nullable Drawable icon) {
            this.listener = listener;
            this.iconView = iconView;
            this.icon = icon;
        }
    }

    @Nullable
    private static View.OnClickListener currentClickListener(@NonNull View row) {
        try {
            Object info = getFieldValue(row, "mListenerInfo");
            Object value = getFieldValue(info, "mOnClickListener");
            return value instanceof View.OnClickListener ? (View.OnClickListener) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void setBorrowedClickListener(@NonNull View row, @NonNull View.OnClickListener listener) {
        row.setOnClickListener(listener);
        OriginalSettingsRow original = borrowedSettingsRows.get(row);
        if (original != null) original.borrowed = listener;
    }

    @SuppressLint("DiscouragedApi")
    private void rememberOriginalSettingsRow(@NonNull View row) {
        if (borrowedSettingsRows.containsKey(row)) return;
        View.OnClickListener listener = currentClickListener(row);
        int iconId = row.getResources().getIdentifier("icon", "id", Constants.THEME_STORE_PACKAGE);
        View iconView = iconId == 0 ? null : row.findViewById(iconId);
        ImageView image = iconView instanceof ImageView ? (ImageView) iconView : null;
        borrowedSettingsRows.put(row, new OriginalSettingsRow(
                listener, image, image == null ? null : image.getDrawable()));
    }

    private void restoreThemeSettingsRow(@Nullable Object holder) {
        Object itemViewValue = getFieldValue(holder, "itemView");
        if (!(itemViewValue instanceof View row)) return;
        OriginalSettingsRow original = borrowedSettingsRows.remove(row);
        if (original == null) return;
        View.OnClickListener current = currentClickListener(row);
        if (current != null && current == original.borrowed) row.setOnClickListener(original.listener);
        if (original.iconView != null) original.iconView.setImageDrawable(original.icon);
    }

    @SuppressLint("DiscouragedApi")
    private void bindThemeSettingsShortcutRow(@Nullable Object holder) {
        Object itemViewValue = getFieldValue(holder, "itemView");
        if (!(itemViewValue instanceof View row)) return;
        rememberOriginalSettingsRow(row);
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
        setBorrowedClickListener(row, v -> {
            try {
                v.getContext().startActivity(moduleSettingsIntent());
            } catch (Throwable e) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "MiBackscreen theme settings entry launch failed", e);
                ModuleStrings.showOpenFailure(v.getContext());
            }
        });
    }

    @SuppressLint("DiscouragedApi")
    private void bindThemeAiShortcutRow(@Nullable Object holder) {
        Object itemViewValue = getFieldValue(holder, "itemView");
        if (!(itemViewValue instanceof View row)) return;
        rememberOriginalSettingsRow(row);
        Context context = row.getContext();
        int titleId = context.getResources().getIdentifier(
                "title", "id", Constants.THEME_STORE_PACKAGE);
        if (titleId != 0) {
            View titleView = row.findViewById(titleId);
            if (titleView instanceof TextView) {
                ((TextView) titleView).setText(ModuleStrings.get(context, "ai_rear_screen_title", "AI陪伴", "AI Companion"));
            }
        }
        setBorrowedClickListener(row, v -> {
            try {
                Intent intent = resolveRearScreenAiIntent(v.getContext().getClassLoader());
                if (intent != null) {
                    v.getContext().startActivity(intent);
                } else {
                    ModuleStrings.showOpenFailure(v.getContext());
                }
            } catch (Throwable e) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "AI rear screen entry launch failed", e);
                ModuleStrings.showOpenFailure(v.getContext());
            }
        });
    }

    /**
     * 设置页的顶部预览来自主题商店数据库（按 position 降序），并不直接采用
     * theme_rear_widget 的 changed 标记。背屏中心完成切换后已经把当前 widget id
     * 同步到 Secure Settings；设置页冷启动前再据此提升数据库中的对应项。
     */
    public void installThemeSettingsSelectionSyncHook(@NonNull ClassLoader classLoader) {
        Class<?> activityClass = findClass(Constants.THEME_REAR_SETTING_ACTIVITY, classLoader);
        if (activityClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings sync target missing: " + Constants.THEME_REAR_SETTING_ACTIVITY);
            return;
        }

        module.hookMethodIfPresent(
                activityClass,
                "onCreate",
                new Class[]{Bundle.class},
                Constants.THEME_REAR_SETTING_ACTIVITY + "#onCreate",
                chain -> {
                    appIndex.syncAiGeneratedAppCardsToSubscreenIndex();
                    if (PrefsBridge.shouldFixRearScreenApply(module)) {
                        resourceRepair.grantRearScreenRuntimeAccess();
                        resourceRepair.repairAiMateSnapshots();
                        if (chain.getThisObject() instanceof Activity activity) {
                            runThemeDatabaseSelectionSync(activity, classLoader);
                        }
                    }
                    return chain.proceed();
                }
        );

        module.hookMethodIfPresent(
                activityClass,
                "onResume",
                new Class[]{},
                Constants.THEME_REAR_SETTING_ACTIVITY + "#onResume",
                chain -> {
                    appIndex.syncAiGeneratedAppCardsToSubscreenIndex();
                    if (PrefsBridge.shouldFixRearScreenApply(module)) {
                        resourceRepair.grantRearScreenRuntimeAccess();
                        resourceRepair.repairAiMateSnapshots();
                        if (chain.getThisObject() instanceof Activity activity) {
                            runThemeDatabaseSelectionSync(activity, classLoader);
                        }
                    }
                    return chain.proceed();
                }
        );
    }

    /**
     * AI 应用卡链路的「18 Pro Max 设备身份伪装」开关。
     * <p>
     * 这个伪装是**页面级**的：只要用户还在 AI 应用卡链路里，所有经 DeviceUtils 取设备名的请求
     * 都要声称自己是 18 Pro Max（服务端据此发 madrid 设备池）；离开后必须立刻恢复真实身份，
     * 否则主题商店的背屏壁纸列表与详情也会跟着声称自己是 18 Pro Max，服务端改发 madrid 池——
     * 表现就是「背屏创意设计大赛」点进去无法显示、刷新后整个列表换成 18 系列。
     * <p>
     * 这里踩过三个坑，都别再犯：
     * <ol>
     *   <li><b>复位钩子必须沿继承链找。</b>宿主只在真正覆写时才声明生命周期方法：
     *       {@code RearScreenAiAppListActivity} 声明 onCreate/onResume/onStop（无 onPause/onDestroy），
     *       {@code RearScreenAiAppDetailActivity} 只声明了 onCreate——它连 onStop 都没有，
     *       退出靠父类 {@code AbstractBaseActivity}。早期只在本类上找 onPause/onDestroy，
     *       找不到就静默跳过，于是标志位一旦置 true 就再也回不来。</li>
     *   <li><b>不能用单个 boolean。</b>列表页打开详情页时，列表页的 onStop 会晚于详情页的
     *       onResume，简单开关会被交错顺序反转。改成「活跃 AI 页面实例集合」，集合清空才算离开。</li>
     *   <li><b>进入钩子只能挂本类声明的。</b>退出钩子挂父类是安全的（不在集合里的实例删了等于没删），
     *       但若把父类的 onResume 拿来当进入钩子，主题商店每个 Activity 都会被塞进集合，恒定常开。</li>
     * </ol>
     */
    public void installThemeAiAppIndexSyncHooks(@NonNull ClassLoader classLoader) {
        boolean anyTarget = false;
        for (String candidate : Constants.THEME_AI_APP_FLOW_ACTIVITIES) {
            Class<?> activityClass = findClass(candidate, classLoader);
            if (activityClass == null) continue;
            anyTarget = true;
            installThemeAiAppLifecycleHooks(activityClass);
        }
        if (!anyTarget) {
            module.log(Log.DEBUG, Constants.LOG_TAG, "Theme AI app index sync target missing");
        }
    }

    private void installThemeAiAppLifecycleHooks(@NonNull Class<?> activityClass) {
        Method exit = findLifecycleHandlerInHierarchy(
                activityClass, Constants.THEME_AI_APP_EXIT_METHODS);
        if (exit == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "AI app page has no usable exit hook, skip identity spoof on "
                            + activityClass.getName());
            return;
        }
        module.hookMethodIfPresent(
                exit,
                exit.getDeclaringClass().getName() + "#" + exit.getName()
                        + " -> " + activityClass.getSimpleName(),
                chain -> {
                    markThemeAiAppFlowVisible(chain.getThisObject(), false);
                    return chain.proceed();
                }
        );
        module.hookMethodIfPresent(
                activityClass,
                "onCreate",
                new Class[]{Bundle.class},
                activityClass.getName() + "#onCreate",
                chain -> {
                    markThemeAiAppFlowVisible(chain.getThisObject(), true);
                    appIndex.syncAiGeneratedAppCardsToSubscreenIndex();
                    return chain.proceed();
                }
        );
        module.hookMethodIfPresent(
                activityClass,
                "onResume",
                new Class[]{},
                activityClass.getName() + "#onResume",
                chain -> {
                    markThemeAiAppFlowVisible(chain.getThisObject(), true);
                    appIndex.syncAiGeneratedAppCardsToSubscreenIndex();
                    return chain.proceed();
                }
        );
    }

    /**
     * 按「方法优先级优先、继承层级其次」找退出点：先沿整条继承链找 onStop，再找 onPause，
     * 最后才退到 onDestroy。这样即便某个页面自己声明了 onDestroy，只要父类有 onStop 也优先用
     * onStop（页面不可见就复位，比等销毁及时）。
     */
    @Nullable
    private static Method findLifecycleHandlerInHierarchy(
            @NonNull Class<?> targetClass,
            @NonNull String[] methodNames
    ) {
        for (String name : methodNames) {
            for (Class<?> current = targetClass;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                Method method = findDeclaredMethod(current, name);
                if (method != null) return method;
            }
        }
        return null;
    }

    private void markThemeAiAppFlowVisible(@Nullable Object activity, boolean entered) {
        if (activity == null) return;
        boolean visible;
        synchronized (themeAiAppFlowActivities) {
            if (entered) {
                themeAiAppFlowActivities.add(activity);
            } else {
                themeAiAppFlowActivities.remove(activity);
            }
            visible = !themeAiAppFlowActivities.isEmpty();
        }
        markThemeAiAppPageVisible(visible);
    }

    private void markThemeAiAppPageVisible(boolean visible) {
        if (themeAiAppPageVisible == visible) return;
        themeAiAppPageVisible = visible;
        module.log(Log.DEBUG, Constants.LOG_TAG,
                "AI app page identity spoof " + (visible ? "ON" : "OFF"));
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

        pendingSelection.set(selectedId);
        startSelectionSync(classLoader);
    }

    private void startSelectionSync(ClassLoader classLoader) {
        if (!selectionSyncRunning.compareAndSet(false, true)) return;
        try {
            BackgroundTasks.execute(() -> {
                try {
                    Integer next;
                    while ((next = pendingSelection.getAndSet(null)) != null) {
                        if (syncThemeDatabaseToRearSelection(classLoader, next)) {
                            lastThemeDatabaseSyncedWidgetId = next;
                            lastThemeDatabaseSyncedAtElapsed = SystemClock.elapsedRealtime();
                        }
                    }
                } finally {
                    selectionSyncRunning.set(false);
                    if (pendingSelection.get() != null) startSelectionSync(classLoader);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            selectionSyncRunning.set(false);
            module.log(Log.WARN, Constants.LOG_TAG, "Selection sync worker busy", error);
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
            module.log(Log.WARN, Constants.LOG_TAG, "readSelectedRearWidgetId failed", e);
            return null;
        }
    }

    private boolean syncThemeDatabaseToRearSelection(
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
            List<?> items = callRearListGetter(manager);
            if (items == null) return false;

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
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Synced Theme DB to rear selection: id=" + selectedId
                            + ", position=" + selectedPosition + "->" + promotedPosition);
            return true;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "syncThemeDatabaseToRearSelection failed", e);
            return false;
        }
    }
}
