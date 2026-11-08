package hook.HyperBackscreen.hook.settings;

import static hook.HyperBackscreen.hook.util.HookReflection.findClass;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.ModuleStrings;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 系统「设置」首页里的 MiBackscreen 入口。
 *
 * <p>首页列表由 {@code MiuiSettings#updateHeaderList(List<Header>)} 整理，每次进入首页都会重新调用；
 * 在它之后按用户选的位置插入一条 Header：
 * <ul>
 *   <li>顶部：「我的设备」之后（背屏设置也在这一组）；</li>
 *   <li>中部：「桌面」之后；</li>
 *   <li>底部：「应用使用时长」之后，找不到时放在列表末尾。</li>
 * </ul>
 * 锚点按资源名查 id，不依赖混淆名。图标不能用模块资源 id（设置进程按自己的资源表解析），所以先借一个
 * 设置自带图标占位，再在 {@code HeaderAdapter#setIcon} 之后换成模块自己的图标。
 */
public final class SettingsEntryHooks {
    /** 入口 Header 的 id：任意不会和资源 id 撞上的固定值（"MiBs"）。 */
    private static final long HEADER_ID = 0x4D694273L;

    private final ModuleMain module;
    private volatile boolean installed;

    public SettingsEntryHooks(@NonNull ModuleMain module) {
        this.module = module;
    }

    public void installOnce(@NonNull ClassLoader classLoader) {
        if (installed) return;
        synchronized (this) {
            if (installed) return;
            installed = true;
        }
        Class<?> miuiSettings = findClass(Constants.SETTINGS_MIUI_SETTINGS_CLASS, classLoader);
        Class<?> headerClass = findClass(Constants.SETTINGS_HEADER_CLASS, classLoader);
        if (miuiSettings == null || headerClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Settings entry hook targets missing");
            return;
        }
        module.hookMethodIfPresent(
                miuiSettings,
                "updateHeaderList",
                new Class[]{List.class},
                Constants.SETTINGS_MIUI_SETTINGS_CLASS + "#updateHeaderList",
                chain -> {
                    Object result = chain.proceed();
                    Object list = chain.getArgs().get(0);
                    if (list instanceof List<?> && chain.getThisObject() instanceof Activity activity) {
                        @SuppressWarnings("unchecked")
                        List<Object> headers = (List<Object>) list;
                        insertEntry(activity, headerClass, headers);
                    }
                    return result;
                }
        );

        Class<?> adapterClass = findClass(Constants.SETTINGS_HEADER_ADAPTER_CLASS, classLoader);
        Class<?> holderClass = findClass(Constants.SETTINGS_HEADER_VIEW_HOLDER_CLASS, classLoader);
        if (adapterClass == null || holderClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Settings header adapter missing; entry keeps placeholder icon");
            return;
        }
        module.hookMethodIfPresent(
                adapterClass,
                "setIcon",
                new Class[]{holderClass, headerClass},
                Constants.SETTINGS_HEADER_ADAPTER_CLASS + "#setIcon",
                chain -> {
                    Object result = chain.proceed();
                    Object holder = chain.getArgs().get(0);
                    Object header = chain.getArgs().get(1);
                    if (header != null && readLong(header, "id") == HEADER_ID) {
                        applyModuleIcon(holder);
                    }
                    return result;
                }
        );
    }

    private void insertEntry(@NonNull Activity activity, @NonNull Class<?> headerClass, @NonNull List<Object> headers) {
        try {
            // 列表每次都会重建，但防止同一次里被调用两次时重复插入。
            for (Object header : headers) {
                if (readLong(header, "id") == HEADER_ID) return;
            }
            String position = PrefsBridge.readSettingsEntryForHook(module);
            if ("off".equals(position)) return;

            Context context = activity;
            String anchorName = switch (position) {
                case "top" -> "my_device";
                case "middle" -> "launcher_settings";
                default -> "app_timer";
            };
            int anchorId = context.getResources().getIdentifier(anchorName, "id", context.getPackageName());
            int index = -1;
            for (int i = 0; i < headers.size(); i++) {
                if (anchorId != 0 && readLong(headers.get(i), "id") == anchorId) {
                    index = i + 1;
                    break;
                }
            }
            if (index < 0) index = headers.size();
            // 和相邻条目同组，分组卡片才不会在入口处断开。
            Object neighbour = headers.isEmpty() ? null : headers.get(Math.max(0, index - 1));

            Object header = headerClass.getDeclaredConstructor().newInstance();
            writeField(header, "id", HEADER_ID);
            writeField(header, "title", ModuleStrings.get(context, "app_name", "MiBackscreen", "MiBackscreen"));
            writeField(header, "summary", null);
            writeField(header, "iconRes", placeholderIcon(headers, neighbour));
            if (neighbour != null) writeField(header, "groupId", readInt(neighbour, "groupId"));
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(
                    Constants.MODULE_PACKAGE, Constants.MODULE_PACKAGE + ".ui.MainActivity"));
            intent.putExtra("isDisplayHomeAsUpEnabled", true);
            writeField(header, "intent", intent);
            // 设置首页按 extras 里的 header_user 判断条目属于哪个用户，不给会被当成无效条目过滤掉。
            Bundle extras = new Bundle();
            ArrayList<UserHandle> users = new ArrayList<>();
            users.add(Process.myUserHandle());
            extras.putParcelableArrayList("header_user", users);
            writeField(header, "extras", extras);

            headers.add(index, header);
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG, "Settings entry insert failed", error);
        }
    }

    /** 占位图标：借用邻近条目（或任意有图标的条目）的图标资源，保证图标位可见，真正的图标在 setIcon 后替换。 */
    private static int placeholderIcon(@NonNull List<Object> headers, @Nullable Object neighbour) {
        int icon = neighbour == null ? 0 : readInt(neighbour, "iconRes");
        if (icon != 0) return icon;
        for (Object header : headers) {
            icon = readInt(header, "iconRes");
            if (icon != 0) return icon;
        }
        return android.R.drawable.sym_def_app_icon;
    }

    private void applyModuleIcon(@Nullable Object holder) {
        Object view = readObject(holder, "icon");
        if (!(view instanceof ImageView icon)) return;
        Bitmap bitmap = moduleIcon(icon.getContext());
        if (bitmap != null) icon.setImageBitmap(bitmap);
    }

    @Nullable
    private volatile Bitmap cachedIcon;

    /**
     * 首页图标：取模块面板图标里的 Logo 本体——把和图片边缘连通的白底抠成透明、裁掉留白，
     * 再按设置自己的 {@code header_icon_size} 等比缩放到满宽、居中。直接塞原图 ImageView 会按原始像素
     * 显示（图标大出一圈）；整张缩小又会因为四周白边让 Logo 只剩一点、白底和卡片融在一起。
     */
    @Nullable
    private Bitmap moduleIcon(@NonNull Context context) {
        Bitmap cached = cachedIcon;
        if (cached != null && !cached.isRecycled()) return cached;
        try {
            Resources hostRes = context.getResources();
            int sizeId = hostRes.getIdentifier("header_icon_size", "dimen", context.getPackageName());
            int size = sizeId != 0
                    ? hostRes.getDimensionPixelSize(sizeId)
                    : Math.round(28 * hostRes.getDisplayMetrics().density);
            Resources moduleRes = context.createPackageContext(Constants.MODULE_PACKAGE, 0).getResources();
            int id = moduleRes.getIdentifier("panel_icon", "drawable", Constants.MODULE_PACKAGE);
            if (id == 0 || size <= 0) return null;
            Bitmap source = android.graphics.BitmapFactory.decodeResource(moduleRes, id);
            if (source == null) return null;
            Bitmap logo = extractLogo(source);
            float scale = Math.min((float) size / logo.getWidth(), (float) size / logo.getHeight());
            int w = Math.max(1, Math.round(logo.getWidth() * scale));
            int h = Math.max(1, Math.round(logo.getHeight() * scale));
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            int left = (size - w) / 2;
            int top = (size - h) / 2;
            canvas.drawBitmap(logo, null, new android.graphics.Rect(left, top, left + w, top + h), paint);
            cachedIcon = bitmap;
            return bitmap;
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG, "Settings entry icon unavailable", error);
            return null;
        }
    }

    /** 从边缘开始把连通的近白像素抠成透明（Logo 内部的白色被渐变边框围住，不受影响），再裁到不透明区域。 */
    @NonNull
    private static Bitmap extractLogo(@NonNull Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        boolean[] visited = new boolean[pixels.length];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int x = 0; x < width; x++) {
            queue.add(x);
            queue.add((height - 1) * width + x);
        }
        for (int y = 0; y < height; y++) {
            queue.add(y * width);
            queue.add(y * width + width - 1);
        }
        while (!queue.isEmpty()) {
            int index = queue.poll();
            if (visited[index]) continue;
            visited[index] = true;
            if (!isBackground(pixels[index])) continue;
            pixels[index] = 0;
            int x = index % width;
            int y = index / width;
            if (x > 0) queue.add(index - 1);
            if (x < width - 1) queue.add(index + 1);
            if (y > 0) queue.add(index - width);
            if (y < height - 1) queue.add(index + width);
        }
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) > 24) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return source;
        Bitmap cleared = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        return Bitmap.createBitmap(cleared, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** 底色：透明，或接近纯白（含 Logo 外围的浅灰投影）。 */
    private static boolean isBackground(int color) {
        int alpha = color >>> 24;
        if (alpha < 24) return true;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return r > 232 && g > 232 && b > 232;
    }

    private static long readLong(@Nullable Object target, @NonNull String name) {
        Object value = readObject(target, name);
        return value instanceof Long number ? number : Long.MIN_VALUE;
    }

    private static int readInt(@Nullable Object target, @NonNull String name) {
        Object value = readObject(target, name);
        return value instanceof Integer number ? number : 0;
    }

    @Nullable
    private static Object readObject(@Nullable Object target, @NonNull String name) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), name);
            return field == null ? null : field.get(target);
        } catch (Throwable error) {
            return null;
        }
    }

    private static void writeField(@NonNull Object target, @NonNull String name, @Nullable Object value)
            throws IllegalAccessException {
        Field field = findField(target.getClass(), name);
        if (field != null) field.set(target, value);
    }

    @Nullable
    private static Field findField(@NonNull Class<?> type, @NonNull String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 往父类找
            }
        }
        return null;
    }
}
