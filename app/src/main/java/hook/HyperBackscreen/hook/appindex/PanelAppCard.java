package hook.HyperBackscreen.hook.appindex;

import android.content.Context;
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
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.panel.SwipePanelHost;
/**
 * 背屏上滑面板里的 MiBackscreen 官方应用卡。
 *
 * 面板条目是 {@code e2.a}：{@code a}=名称、{@code c}=多语言名称表、{@code e}=图标路径、
 * {@code g}/{@code h}=明暗预览图、{@code l}=bindApp、{@code k}=appCardType、{@code m}=Intent，
 * 由 {@code e2.k} 单例持有。模块把固定 {@code resId} 写入 {@code appInfo.json}，点击时由
 * {@code e2.l.onClick} Hook 接回现有快捷面板。
 */
public final class PanelAppCard {
    private PanelAppCard() {
    }

    /** 该条目是不是模块自己的（优先按固定 resId，兼容 bindApp）。 */
    public static boolean isModuleEntry(@NonNull ClassLoader classLoader, @Nullable Object entry) {
        try {
            return isModuleEntry(entry, loadClass(classLoader, Constants.SUBSCREEN_LAUNCHER_ENTRY_CLASS));
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel entry check failed", e);
            return false;
        }
    }

    private static boolean isModuleEntry(@Nullable Object element, @NonNull Class<?> entryClass) {
        if (!entryClass.isInstance(element)) return false;
        Object resId = readField(element, Constants.SUBSCREEN_LAUNCHER_ENTRY_RES_ID_FIELD);
        if (Constants.MODULE_PANEL_CARD_RES_ID.equals(resId)) return true;
        return Constants.MODULE_PACKAGE.equals(readField(element,
                Constants.SUBSCREEN_LAUNCHER_ENTRY_BIND_APP_FIELD));
    }

    public static boolean isModuleCardInfo(@NonNull org.json.JSONObject item) {
        return Constants.MODULE_PANEL_CARD_RES_ID.equals(item.optString("resId", ""))
                || Constants.MODULE_PACKAGE.equals(item.optString("bindApp", ""));
    }

    /** 上一次导出预览图时的内容签名（语言 + 各卡片取值 + 模块版本）；签名不变且文件还在就不重画。 */
    @Nullable
    private static volatile String lastPreviewSignature;
    /** 上一次导出资源包时的描述内容；不变就不重写 zip。 */
    @Nullable
    private static volatile String lastPackageContent;

    /**
     * 确保官方应用卡资源齐全。这条路径挂在宿主高频调用上，所以只在内容真的变了时才写盘：
     * 图标只导一次；预览图按签名判断；描述 / 元数据 / 资源包按内容比较。
     */
    public static boolean ensureOfficialResources() {
        Context context = hostContext();
        if (context == null) return false;
        String description = buildDescriptionXml(context);
        String meta = buildMetaJson(context);
        return exportOfficialImage(context, Constants.MODULE_PANEL_CARD_ICON, false, () -> renderSquare(context))
                && ensurePreviews(context, false)
                && exportTextFile(Constants.MODULE_PANEL_CARD_DESCRIPTION, description, true)
                && exportTextFile(Constants.MODULE_PANEL_CARD_META, meta, true)
                && exportRearScreenPackage(context, description, meta);
    }

    private static synchronized boolean ensurePreviews(@NonNull Context context, boolean force) {
        String signature = SwipePanelHost.previewSignature(context);
        boolean present = new File(Constants.MODULE_PANEL_CARD_PREVIEW_LIGHT).isFile()
                && new File(Constants.MODULE_PANEL_CARD_PREVIEW_DARK).isFile();
        if (!force && present && signature.equals(lastPreviewSignature)) return true;
        boolean ok = exportOfficialImage(context, Constants.MODULE_PANEL_CARD_PREVIEW_LIGHT,
                true, () -> renderPreview(context, false))
                && exportOfficialImage(context, Constants.MODULE_PANEL_CARD_PREVIEW_DARK,
                true, () -> renderPreview(context, true));
        if (ok) lastPreviewSignature = signature;
        return ok;
    }

    @Nullable
    public static org.json.JSONObject createOfficialAppInfo() {
        Context context = hostContext();
        if (context == null) return null;
        try {
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("resId", Constants.MODULE_PANEL_CARD_RES_ID);
            item.put("appName", moduleLabel(context));
            item.put("mtzPath", Constants.MODULE_PANEL_CARD_RESOURCE);
            item.put("appIconPath", Constants.MODULE_PANEL_CARD_ICON);
            item.put("previewLightPath", Constants.MODULE_PANEL_CARD_PREVIEW_LIGHT);
            item.put("previewDarkPath", Constants.MODULE_PANEL_CARD_PREVIEW_DARK);
            item.put("bindApp", Constants.MODULE_PACKAGE);
            item.put("isGame", false);
            item.put("isPreset", false);
            item.put("appCardType", Constants.SUBSCREEN_LAUNCHER_ENTRY_ACTIVITY_CARD_TYPE);
            return item;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Official panel card appInfo build failed", e);
            return null;
        }
    }

    private static synchronized boolean exportTextFile(
            @NonNull String path,
            @NonNull String content,
            boolean replaceExisting
    ) {
        File target = new File(path);
        if (!replaceExisting && target.isFile() && target.length() > 0) {
            grantReadable(target);
            return true;
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (target.isFile() && target.length() == bytes.length && sameContent(target, bytes)) {
            return true; // 内容没变就不重写
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        grantDirectoryAccess(parent);
        File tmp = tempFor(target);
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(bytes);
            out.getFD().sync();
            grantReadable(tmp);
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            grantReadable(target);
            return true;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Official panel card text export failed: " + path, e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    private static boolean sameContent(@NonNull File file, @NonNull byte[] expected) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] actual = new byte[expected.length];
            int read = 0;
            while (read < actual.length) {
                int n = in.read(actual, read, actual.length - read);
                if (n < 0) return false;
                read += n;
            }
            return java.util.Arrays.equals(actual, expected) && in.read() < 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 临时文件名带进程与线程号，避免两个进程同时导出时互相截断。 */
    @NonNull
    private static File tempFor(@NonNull File target) {
        return new File(target.getAbsolutePath() + ".mibackscreen." + android.os.Process.myPid()
                + "." + Thread.currentThread().getId() + ".tmp");
    }

    private static synchronized boolean exportRearScreenPackage(
            @NonNull Context context,
            @NonNull String description,
            @NonNull String meta
    ) {
        File target = new File(Constants.MODULE_PANEL_CARD_RESOURCE);
        String content = description + "\u0000" + meta;
        if (target.isFile() && content.equals(lastPackageContent)) return true;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        grantDirectoryAccess(parent);
        File tmp = tempFor(target);
        try (FileOutputStream file = new FileOutputStream(tmp, false);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("description.xml"));
            zip.write(description.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            // rename 之前先落盘，掉电时不会留下一个空的资源包。
            file.getFD().sync();
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Official panel card package export failed", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        grantReadable(tmp);
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        grantReadable(target);
        lastPackageContent = content;
        return true;
    }

    private static synchronized boolean exportOfficialImage(
            @NonNull Context context,
            @NonNull String path,
            boolean replaceExisting,
            @NonNull Supplier<Bitmap> render
    ) {
        File target = new File(path);
        if (!replaceExisting && target.isFile() && target.length() > 0) {
            grantReadable(target);
            return true;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        grantDirectoryAccess(parent);
        Bitmap bitmap = render.get();
        if (bitmap == null) return false;
        File tmp = tempFor(target);
        boolean replaced = false;
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
            out.getFD().sync();
            grantReadable(tmp);
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            grantReadable(target);
            replaced = true;
            return true;
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Official panel card image export failed: " + path, e);
            return false;
        } finally {
            bitmap.recycle();
            // 压缩失败 / 异常时清掉临时文件，不在资源目录里留垃圾。
            if (!replaced) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static void grantDirectoryAccess(@Nullable File dir) {
        File current = dir;
        while (current != null
                && current.getAbsolutePath().startsWith(Constants.THEME_MAGIC_DIR + "/")) {
            current.setReadable(true, false);
            current.setWritable(true, true);
            current.setExecutable(true, false);
            current = current.getParentFile();
        }
    }

    private static void grantReadable(@NonNull File file) {
        grantDirectoryAccess(file.getParentFile());
        file.setReadable(true, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    @NonNull
    private static String buildDescriptionXml(@NonNull Context context) {
        String title = moduleLabel(context);
        String description = "打开背屏快捷面板";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<theme>\n"
                + "<osVersion>3</osVersion>\n"
                + "<version><![CDATA[1]]></version>\n"
                + "<uiVersion>15</uiVersion>\n"
                + "<author><![CDATA[MiBackscreen]]></author>\n"
                + "<designer><![CDATA[MiBackscreen]]></designer>\n"
                + "<title><![CDATA[" + cdata(title) + "]]></title>\n"
                + "<description><![CDATA[" + cdata(description) + "]]></description>\n"
                + "<appName><![CDATA[" + cdata(title) + "]]></appName>\n"
                + "<bindApp>" + Constants.MODULE_PACKAGE + "</bindApp>\n"
                + "<frame>15</frame>\n"
                + "<editable>false</editable>\n"
                + "<resourceType>rearscreen</resourceType>\n"
                + "<subResourceType>app_card</subResourceType>\n"
                + "<isLightApp>true</isLightApp>\n"
                + "<bindAppVersion>1</bindAppVersion>\n"
                + "<isThirdParties>true</isThirdParties>\n"
                + "<rearScreenVersion>0</rearScreenVersion>\n"
                + "<thmAppVersion>0</thmAppVersion>\n"
                + "<deviceRelatedRearScreen>popsicle:rearscreen</deviceRelatedRearScreen>\n"
                + "<authors><author locale=\"zh_CN\"><![CDATA[MiBackscreen]]></author></authors>\n"
                + "<designers><designer locale=\"zh_CN\"><![CDATA[MiBackscreen]]></designer></designers>\n"
                + "<titles><title locale=\"zh_CN\"><![CDATA[" + cdata(title) + "]]></title></titles>\n"
                + "<descriptions><description locale=\"zh_CN\"><![CDATA[" + cdata(description)
                + "]]></description></descriptions>\n"
                + "<appNames><appName locale=\"zh_CN\"><![CDATA[" + cdata(title)
                + "]]></appName></appNames>\n"
                + "<funcDescs><funcDesc locale=\"zh_CN\"/></funcDescs>\n"
                + "<briefs><brief locale=\"zh_CN\"/></briefs>\n"
                + "<visualStyles><visualStyle locale=\"zh_CN\"/></visualStyles>\n"
                + "<colorSchemes><colorScheme locale=\"zh_CN\"/></colorSchemes>\n"
                + "</theme>\n";
    }

    @NonNull
    private static String buildMetaJson(@NonNull Context context) {
        String title = moduleLabel(context);
        String description = "打开背屏快捷面板";
        try {
            org.json.JSONObject titleMap = new org.json.JSONObject()
                    .put("fallback", title)
                    .put("zh_CN", title);
            org.json.JSONObject descMap = new org.json.JSONObject()
                    .put("fallback", description)
                    .put("zh_CN", description);
            org.json.JSONObject authorMap = new org.json.JSONObject()
                    .put("fallback", "MiBackscreen")
                    .put("zh_CN", "MiBackscreen");
            org.json.JSONObject meta = new org.json.JSONObject()
                    .put("authorMap", authorMap)
                    .put("autoTheme", false)
                    .put("bindAction", "")
                    .put("bindApp", Constants.MODULE_PACKAGE)
                    .put("bindAppPermission", "")
                    .put("desc", description)
                    .put("descMap", descMap)
                    .put("designerMap", authorMap)
                    .put("editable", false)
                    .put("id", "back_screen_card_" + Constants.MODULE_PANEL_CARD_RES_ID)
                    .put("isCustomEdit", false)
                    .put("title", title)
                    .put("titleMap", titleMap)
                    .put("typeTag", "")
                    .put("versionCode", 1)
                    .put("widgetCategory", "");
            return meta.toString();
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Official panel card meta build failed", e);
            return "{}";
        }
    }

    @NonNull
    private static String cdata(@NonNull String value) {
        return value.replace("]]>", "]]]]><![CDATA[>");
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

    /**
     * 应用卡缩略图：按真实面板排版现画（语言、深浅色、各开关的当前状态都与面板一致），
     * 明暗各一张；面板画不出来时退回到「底色 + 居中图标」。
     */
    @Nullable
    private static Bitmap renderPreview(@NonNull Context context, boolean dark) {
        int width = Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_WIDTH;
        int height = Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_HEIGHT;
        Bitmap panel = SwipePanelHost.renderPreview(context, dark, width, height);
        if (panel != null) return panel;
        Drawable icon = modulePanelIcon(context);
        if (icon == null) return null;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(dark
                ? Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_BACKGROUND
                : Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_BACKGROUND);
        int size = Math.round(height * Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_ICON_RATIO);
        int left = (width - size) / 2;
        int top = (height - size) / 2;
        icon.setBounds(left, top, left + size, top + size);
        icon.draw(canvas);
        return bitmap;
    }

    /**
     * 面板里改了设置之后重画两张缩略图，让「我的应用卡」里的预览跟上真实状态。
     * 只在卡片资源已经导出过（即用户添加过这张卡）时才重画；需在后台线程调用。
     */
    public static void refreshPreviews() {
        Context context = hostContext();
        if (context == null) return;
        if (!new File(Constants.MODULE_PANEL_CARD_PREVIEW_LIGHT).isFile()) return;
        ensurePreviews(context, false);
    }

    /** 面板图标取自模块 APK 自己的资源，宿主资源里没有这张图。 */
    @Nullable
    private static Drawable modulePanelIcon(@NonNull Context context) {
        try {
            Context moduleContext = context.createPackageContext(Constants.MODULE_PACKAGE, 0);
            int id = moduleContext.getResources().getIdentifier("panel_icon", "drawable", Constants.MODULE_PACKAGE);
            return id == 0 ? null : moduleContext.getResources().getDrawable(id, null);
        } catch (Throwable e) {
            Log.w(Constants.LOG_TAG, "Module panel icon unavailable", e);
            return null;
        }
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
