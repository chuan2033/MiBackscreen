package hook.HyperBackscreen.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import hook.HyperBackscreen.R;
import hook.HyperBackscreen.common.Constants;

/**
 * 背屏上滑面板里的 MiBackscreen 条目。
 *
 * 面板条目是 {@code e2.a}：{@code a}=名称、{@code c}=多语言名称表、{@code e}=图标路径、
 * {@code g}/{@code h}=明暗预览图、{@code l}=bindApp、{@code k}=appCardType、{@code m}=Intent，
 * 由 {@code e2.k} 单例持有。{@code e2.l.onClick} 对 {@code k != 3} 的条目直接
 * {@code startActivity(m)}，所以造一个指向模块的条目即可，点击分派完全走宿主自己的逻辑。
 */
public final class PanelAppCard {

    private static final String ICON_FILE = "mibackscreen_app_icon.png";
    private static final String PREVIEW_LIGHT_FILE = "mibackscreen_preview.png";
    private static final String PREVIEW_DARK_FILE = "mibackscreen_preview_dark.png";

    private PanelAppCard() {
    }

    /** 在卡片列表副本顶部插入模块条目；已存在或造不出来时返回 null。 */
    @Nullable
    public static List<Object> withModuleEntry(
            @NonNull ClassLoader classLoader,
            @Nullable List<?> source
    ) {
        if (source == null) return null;
        try {
            Class<?> entryClass = loadClass(classLoader, Constants.SUBSCREEN_LAUNCHER_ENTRY_CLASS);
            if (containsModuleEntry(source, entryClass)) return null;
            Object entry = createEntry(entryClass, hostContext());
            if (entry == null) return null;
            List<Object> merged = new ArrayList<>(source.size() + 1);
            merged.add(entry);
            merged.addAll(source);
            return merged;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel entry append failed", e);
            return null;
        }
    }

    /**
     * 包一层分发消费者。
     *
     * {@code e2.k.d(Consumer)} 是背屏上滑面板取数据的那条链（面板初始化会走它），它把内部
     * 列表复制一份交给消费者；往宿主内部列表直接预插会让宿主"列表非空即已加载"的判断失效，
     * 所以条目只补进这份分发副本。
     */
    @NonNull
    public static Consumer<Object> wrapDispatchConsumer(
            @NonNull ClassLoader classLoader,
            @NonNull Consumer<?> delegate
    ) {
        return list -> {
            appendToDispatchCopy(classLoader, list);
            asObjectConsumer(delegate).accept(list);
        };
    }

    private static void appendToDispatchCopy(@NonNull ClassLoader classLoader, @Nullable Object list) {
        if (!(list instanceof ArrayList)) return;
        ArrayList<?> entries = (ArrayList<?>) list;
        try {
            Class<?> entryClass = loadClass(classLoader, Constants.SUBSCREEN_LAUNCHER_ENTRY_CLASS);
            if (containsModuleEntry(entries, entryClass)) return;
            Object entry = createEntry(entryClass, hostContext());
            if (entry == null) return;
            addEntry(entries, entry);
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel entry dispatch failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    @NonNull
    private static Consumer<Object> asObjectConsumer(@NonNull Consumer<?> delegate) {
        return (Consumer<Object>) delegate;
    }

    @SuppressWarnings("unchecked")
    private static void addEntry(@NonNull ArrayList<?> entries, @NonNull Object entry) {
        ((ArrayList<Object>) entries).add(0, entry);
    }

    /** 保存路径用：剔除模块条目，避免它被写回 appInfo.json；没剔除到返回 null。 */
    @Nullable
    public static List<Object> withoutModuleEntry(
            @NonNull ClassLoader classLoader,
            @NonNull List<?> source
    ) {
        try {
            Class<?> entryClass = loadClass(classLoader, Constants.SUBSCREEN_LAUNCHER_ENTRY_CLASS);
            List<Object> filtered = new ArrayList<>(source.size());
            boolean dropped = false;
            for (Object element : source) {
                if (isModuleEntry(element, entryClass)) {
                    dropped = true;
                } else {
                    filtered.add(element);
                }
            }
            return dropped ? filtered : null;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel entry strip failed", e);
            return null;
        }
    }

    /** 该条目是不是模块自己的（按 bindApp 字段认）。 */
    public static boolean isModuleEntry(@NonNull ClassLoader classLoader, @Nullable Object entry) {
        try {
            return isModuleEntry(entry, loadClass(classLoader, Constants.SUBSCREEN_LAUNCHER_ENTRY_CLASS));
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel entry check failed", e);
            return false;
        }
    }

    private static boolean containsModuleEntry(@NonNull List<?> source, @NonNull Class<?> entryClass) {
        for (Object element : source) {
            if (isModuleEntry(element, entryClass)) return true;
        }
        return false;
    }

    private static boolean isModuleEntry(@Nullable Object element, @NonNull Class<?> entryClass) {
        return entryClass.isInstance(element)
                && Constants.MODULE_PACKAGE.equals(readField(element,
                        Constants.SUBSCREEN_LAUNCHER_ENTRY_BIND_APP_FIELD));
    }

    @Nullable
    private static Object createEntry(@NonNull Class<?> entryClass, @Nullable Context context) {
        if (context == null) return null;
        try {
            Constructor<?> constructor = entryClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object entry = constructor.newInstance();
            String label = moduleLabel(context);
            String icon = exportImage(context, ICON_FILE, renderSquare(context));
            String previewLight = exportImage(context, PREVIEW_LIGHT_FILE,
                    renderPreview(context, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_BACKGROUND));
            String previewDark = exportImage(context, PREVIEW_DARK_FILE,
                    renderPreview(context, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_BACKGROUND));
            if (icon == null || previewLight == null || previewDark == null) return null;

            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_NAME_FIELD, label);
            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_NAME_MAP_FIELD,
                    buildNameMap(label));
            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_ICON_FIELD, icon);
            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_FIELD, previewLight);
            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_FIELD, previewDark);
            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_BIND_APP_FIELD,
                    Constants.MODULE_PACKAGE);
            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_CARD_TYPE_FIELD,
                    Constants.SUBSCREEN_LAUNCHER_ENTRY_ACTIVITY_CARD_TYPE);

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(
                    Constants.MODULE_PACKAGE, Constants.MODULE_PACKAGE + ".ui.MainActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            writeField(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_INTENT_FIELD, intent);
            return entry;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel entry build failed", e);
            return null;
        }
    }

    /** 渲染成 PNG 落到宿主 cache 目录（同进程同 uid），再把绝对路径交给面板的 Glide。 */
    @Nullable
    private static String exportImage(
            @NonNull Context context,
            @NonNull String name,
            @Nullable Bitmap bitmap
    ) {
        if (bitmap == null) return null;
        File target = new File(context.getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    ? target.getAbsolutePath()
                    : null;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel image export failed: " + name, e);
            return null;
        }
    }

    /** 正方形图标位：直接用模块内置的图标资源，白底与画稿都在资源里排好。 */
    @Nullable
    private static Bitmap renderSquare(@NonNull Context context) {
        Drawable icon = modulePanelIcon(context);
        if (icon == null) return null;
        int size = Constants.SUBSCREEN_LAUNCHER_ENTRY_ICON_SIZE;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Constants.SUBSCREEN_LAUNCHER_ENTRY_ICON_BACKGROUND);
        icon.setBounds(0, 0, size, size);
        icon.draw(canvas);
        return bitmap;
    }

    /** 预览图位：官方是整块背屏 976×596，明暗各一张，图标居中。 */
    @Nullable
    private static Bitmap renderPreview(@NonNull Context context, int background) {
        Drawable icon = modulePanelIcon(context);
        if (icon == null) return null;
        int width = Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_WIDTH;
        int height = Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_HEIGHT;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(background);
        int size = Math.round(height * Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_ICON_RATIO);
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        icon.setBounds(left, top, left + size, top + size);
        icon.draw(canvas);
        return bitmap;
    }

    /** 面板图标取自模块 APK 自己的资源，宿主资源里没有这张图。 */
    @Nullable
    private static Drawable modulePanelIcon(@NonNull Context context) {
        try {
            Context moduleContext = context.createPackageContext(Constants.MODULE_PACKAGE, 0);
            return moduleContext.getResources().getDrawable(R.drawable.panel_icon, null);
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel icon unavailable", e);
            return null;
        }
    }

    /** {@code e2.a.a()} 会拿 {@code a} 当键查 {@code c}，查不到再回退到 "fallback"。 */
    @NonNull
    private static HashMap<String, String> buildNameMap(@NonNull String label) {
        HashMap<String, String> names = new HashMap<>();
        names.put("fallback", label);
        names.put(Locale.getDefault().getLanguage() + "_"
                + Locale.getDefault().getCountry(), label);
        return names;
    }

    @NonNull
    private static String moduleLabel(@NonNull Context context) {
        PackageManager manager = context.getPackageManager();
        try {
            ApplicationInfo info = manager.getApplicationInfo(Constants.MODULE_PACKAGE, 0);
            CharSequence label = manager.getApplicationLabel(info);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module label unavailable", e);
        }
        return Constants.MODULE_PACKAGE;
    }

    /** 宿主进程的 Application。 */
    @Nullable
    private static Context hostContext() {
        try {
            Class<?> activityThread = loadClass(null, "android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object application = current.invoke(null);
            if (application instanceof Context) return (Context) application;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Host application unavailable", e);
        }
        return null;
    }

    /**
     * 用形参转一手再 Class.forName：让 lint 的 PrivateApiDetector 拿不到常量实参，
     * 否则它会把 "e2.a" 当成 Java 类型名去解析并崩溃。
     */
    @NonNull
    private static Class<?> loadClass(@Nullable ClassLoader classLoader, @NonNull String name)
            throws ClassNotFoundException {
        return classLoader != null
                ? Class.forName(name, false, classLoader)
                : Class.forName(name);
    }

    private static void writeField(@NonNull Object target, @NonNull String name, @NonNull Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel entry field missing: " + name, e);
        }
    }

    @Nullable
    private static Object readField(@Nullable Object target, @NonNull String name) {
        if (target == null) return null;
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable e) {
            return null;
        }
    }
}
