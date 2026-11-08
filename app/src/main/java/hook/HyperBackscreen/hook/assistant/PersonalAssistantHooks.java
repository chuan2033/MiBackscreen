package hook.HyperBackscreen.hook.assistant;

import static hook.HyperBackscreen.hook.util.HookReflection.MAX_THEME_INDEX_BYTES;
import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.invokeNoArg;
import static hook.HyperBackscreen.hook.util.HookReflection.readUtf8;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 智能助理（{@code com.miui.personalassistant}）进程里的 Hook：环境信号伪装为 18 Pro 背屏设备，
 * 背屏小部件商店（BackScreenStore）云端 / 预置数据的日志，以及用模块预置 JSON 替换预置分类。
 */
public final class PersonalAssistantHooks {
    private final ModuleMain module;

    public PersonalAssistantHooks(@NonNull ModuleMain module) {
        this.module = module;
    }

    public void installPersonalAssistantStoreHooks(@NonNull ClassLoader classLoader) {
        installPanelCardPreviewRefresh(classLoader);

        Class<?> commonParamsClass = findClass(
                Constants.PERSONAL_ASSISTANT_COMMON_PARAMS_CLASS,
                classLoader);
        if (commonParamsClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Personal assistant hook target missing: "
                            + Constants.PERSONAL_ASSISTANT_COMMON_PARAMS_CLASS);
            return;
        }

        module.hookMethodIfPresent(
                commonParamsClass,
                Constants.PERSONAL_ASSISTANT_ENVIRONMENT_SIGNAL_METHOD,
                new Class[]{Context.class, String.class},
                Constants.PERSONAL_ASSISTANT_COMMON_PARAMS_CLASS + "#"
                        + Constants.PERSONAL_ASSISTANT_ENVIRONMENT_SIGNAL_METHOD,
                chain -> {
                    Object result = chain.proceed();
                    if (result instanceof JSONObject environmentSignal) {
                        if (!PrefsBridge.shouldEnable18ProFeatures(module)) return result;
                        environmentSignal.put("phoneDevice",
                                Constants.PERSONAL_ASSISTANT_REAR_DEVICE);
                        environmentSignal.put("phoneModel",
                                Constants.PERSONAL_ASSISTANT_REAR_MODEL);
                    }
                    return result;
                }
        );

        Class<?> repositoryClass = findClass(
                Constants.PERSONAL_ASSISTANT_STORE_REPOSITORY_CLASS,
                classLoader);
        Class<?> responseClass = findClass(
                Constants.PERSONAL_ASSISTANT_STORE_RESPONSE_CLASS,
                classLoader);
        if (repositoryClass != null && responseClass != null) {
            module.hookMethodIfPresent(
                    repositoryClass,
                    Constants.PERSONAL_ASSISTANT_STORE_CONVERT_METHOD,
                    new Class[]{repositoryClass, responseClass},
                    Constants.PERSONAL_ASSISTANT_STORE_REPOSITORY_CLASS + "#"
                            + Constants.PERSONAL_ASSISTANT_STORE_CONVERT_METHOD,
                    chain -> {
                        Object result = chain.proceed();
                        logBackScreenStoreData("cloud", result);
                        return result;
                    }
            );
        } else {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Personal assistant store repository hook target missing");
        }

        Class<?> presetParserClass = findClass(
                Constants.PERSONAL_ASSISTANT_PRESET_PARSER_CLASS,
                classLoader);
        if (presetParserClass != null) {
            module.hookMethodIfPresent(
                    presetParserClass,
                    Constants.PERSONAL_ASSISTANT_PRESET_PARSE_METHOD,
                    new Class[]{},
                    Constants.PERSONAL_ASSISTANT_PRESET_PARSER_CLASS + "#"
                            + Constants.PERSONAL_ASSISTANT_PRESET_PARSE_METHOD,
                    chain -> {
                        Object result = chain.proceed();
                        if (PrefsBridge.shouldEnable18ProFeatures(module)) {
                            Object modulePreset = parsePersonalAssistantPreset(
                                    classLoader,
                                    presetParserClass,
                                    new File(Constants.PERSONAL_ASSISTANT_PRESET_JSON_PATH));
                            if (modulePreset instanceof List<?> list && !list.isEmpty()) {
                                result = modulePreset;
                            }
                        }
                        logBackScreenStoreData("preset", result);
                        return result;
                    }
            );
        }
    }

    /**
     * 「我的应用卡」点进 MiBackscreen 卡片后，详情页由智能助理显示预览图。它用 Glide 按<b>文件路径</b>
     * 缓存图片（没有带修改时间的签名），而模块每次面板设置变化都在同一路径重画预览 PNG，
     * 所以列表里的小卡片已经是新面板，详情页却一直拿缓存里的旧图。
     *
     * <p>这里在详情页创建前比较模块两张预览图的「修改时间 + 大小」，和上次记下的不一样就清掉
     * 智能助理的 Glide 内存缓存和磁盘缓存，让它重新读文件。Glide 的缓存键算不出来，只能整体清；
     * 但只在预览图真的变过、并且用户打开了应用卡详情时才清一次，代价是其它卡片缩略图重新解码一遍。
     */
    private void installPanelCardPreviewRefresh(@NonNull ClassLoader classLoader) {
        Class<?> detailActivity = findClass(
                Constants.PERSONAL_ASSISTANT_BACKSCREEN_DETAIL_ACTIVITY_CLASS, classLoader);
        if (detailActivity == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Personal assistant hook target missing: "
                    + Constants.PERSONAL_ASSISTANT_BACKSCREEN_DETAIL_ACTIVITY_CLASS);
            return;
        }
        module.hookMethodIfPresent(
                detailActivity,
                "onCreate",
                new Class[]{Bundle.class},
                Constants.PERSONAL_ASSISTANT_BACKSCREEN_DETAIL_ACTIVITY_CLASS + "#onCreate",
                chain -> {
                    if (chain.getThisObject() instanceof Context context) {
                        refreshStalePanelCardPreview(context, classLoader);
                    }
                    return chain.proceed();
                }
        );
    }

    private void refreshStalePanelCardPreview(@NonNull Context context, @NonNull ClassLoader classLoader) {
        try {
            String stamp = previewStamp(Constants.MODULE_PANEL_CARD_PREVIEW_LIGHT)
                    + "|" + previewStamp(Constants.MODULE_PANEL_CARD_PREVIEW_DARK);
            SharedPreferences prefs = context.getSharedPreferences(PANEL_PREVIEW_PREFS, Context.MODE_PRIVATE);
            if (stamp.equals(prefs.getString(PANEL_PREVIEW_STAMP_KEY, null))) return;

            Class<?> glideClass = findClass(Constants.PERSONAL_ASSISTANT_GLIDE_CLASS, classLoader);
            if (glideClass == null) return;
            Object glide = glideClass.getMethod("get", Context.class).invoke(null, context);
            if (glide == null) return;
            // clearMemory 必须在主线程（onCreate 就在主线程）；clearDiskCache 必须在后台线程。
            glideClass.getMethod("clearMemory").invoke(glide);
            Method clearDiskCache = glideClass.getMethod("clearDiskCache");
            Thread clearer = new Thread(() -> {
                try {
                    clearDiskCache.invoke(glide);
                } catch (Throwable error) {
                    module.log(Log.WARN, Constants.LOG_TAG, "Glide disk cache clear failed", error);
                }
            }, "MiBackscreen-GlideClear");
            clearer.start();
            // 等磁盘缓存清完再让详情页去加载，否则它可能抢先读到旧缓存；缓存只有几 MB，通常几十毫秒。
            clearer.join(PANEL_PREVIEW_CLEAR_WAIT_MILLIS);
            prefs.edit().putString(PANEL_PREVIEW_STAMP_KEY, stamp).apply();
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Cleared personal assistant image cache for updated panel card preview");
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG, "Panel card preview refresh failed", error);
        }
    }

    @NonNull
    private static String previewStamp(@NonNull String path) {
        File file = new File(path);
        return file.isFile() ? file.lastModified() + ":" + file.length() : "-";
    }

    private static final String PANEL_PREVIEW_PREFS = "mibackscreen_panel_card_preview";
    private static final String PANEL_PREVIEW_STAMP_KEY = "stamp";
    private static final long PANEL_PREVIEW_CLEAR_WAIT_MILLIS = 800L;

    @Nullable
    private Object parsePersonalAssistantPreset(
            @NonNull ClassLoader classLoader,
            @NonNull Class<?> parserClass,
            @NonNull File jsonFile
    ) {
        if (!jsonFile.isFile()) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "BackScreenStore preset file unavailable: " + jsonFile);
            return null;
        }
        try {
            Class<?> gsonClass = findClass(Constants.PERSONAL_ASSISTANT_GSON_CLASS, classLoader);
            Class<?> presetJsonClass = findClass(
                    Constants.PERSONAL_ASSISTANT_PRESET_JSON_CLASS,
                    classLoader);
            Class<?> presetCategoryClass = findClass(
                    Constants.PERSONAL_ASSISTANT_PRESET_CATEGORY_CLASS,
                    classLoader);
            if (gsonClass == null || presetJsonClass == null || presetCategoryClass == null) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "BackScreenStore preset model classes unavailable");
                return null;
            }

            Object gson = gsonClass.getDeclaredConstructor().newInstance();
            Method fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
            Object presetJson = fromJson.invoke(
                    gson,
                    readUtf8(jsonFile, MAX_THEME_INDEX_BYTES),
                    presetJsonClass);
            Object productsValue = invokeNoArg(presetJson, "getProducts");
            if (!(productsValue instanceof List<?> products) || products.isEmpty()) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "BackScreenStore module preset contains no products");
                return null;
            }

            Method convertCategory = parserClass.getDeclaredMethod(
                    "a",
                    presetCategoryClass,
                    String.class);
            convertCategory.setAccessible(true);
            Locale locale = Locale.getDefault();
            String localeKey = locale.getLanguage() + "_" + locale.getCountry();
            ArrayList<Object> categories = new ArrayList<>();
            for (Object product : products) {
                if (product == null || !presetCategoryClass.isInstance(product)) continue;
                Object category = convertCategory.invoke(null, product, localeKey);
                Object items = invokeNoArg(category, "getItems");
                if (category != null && items instanceof List<?> list && !list.isEmpty()) {
                    categories.add(category);
                }
            }
            return categories.isEmpty() ? null : categories;
        } catch (Throwable throwable) {
            module.log(Log.ERROR, Constants.LOG_TAG,
                    "BackScreenStore module preset parse failed", throwable);
            return null;
        }
    }

    private void logBackScreenStoreData(@NonNull String source, @Nullable Object value) {
        if (!(value instanceof List<?> categories)) {
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "BackScreenStore " + source + ": result is " + value);
            return;
        }
        int itemCount = 0;
        StringBuilder names = new StringBuilder();
        int categoryLimit = Math.min(categories.size(), 8);
        for (int i = 0; i < categoryLimit; i++) {
            Object category = categories.get(i);
            String title = String.valueOf(invokeNoArg(category, "getCategoryTitle"));
            Object itemsValue = invokeNoArg(category, "getItems");
            if (!(itemsValue instanceof List<?> items)) {
                continue;
            }
            itemCount += items.size();
            if (names.length() > 0) names.append(" | ");
            names.append(title).append(": ");
            int itemLimit = Math.min(items.size(), 8);
            for (int j = 0; j < itemLimit; j++) {
                if (j > 0) names.append(", ");
                names.append(invokeNoArg(items.get(j), "getAppName"));
            }
            if (items.size() > itemLimit) {
                names.append("...");
            }
        }
        for (int i = categoryLimit; i < categories.size(); i++) {
            Object itemsValue = invokeNoArg(categories.get(i), "getItems");
            if (itemsValue instanceof List<?> items) {
                itemCount += items.size();
            }
        }
        module.log(Log.INFO, Constants.LOG_TAG,
                "BackScreenStore " + source
                        + ": categories=" + categories.size()
                        + ", items=" + itemCount
                        + ", names=[" + names + "]");
    }
}
