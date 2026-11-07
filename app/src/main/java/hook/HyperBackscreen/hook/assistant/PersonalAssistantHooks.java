package hook.HyperBackscreen.hook.assistant;

import static hook.HyperBackscreen.hook.util.HookReflection.MAX_THEME_INDEX_BYTES;
import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.invokeNoArg;
import static hook.HyperBackscreen.hook.util.HookReflection.readUtf8;

import android.content.Context;
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
