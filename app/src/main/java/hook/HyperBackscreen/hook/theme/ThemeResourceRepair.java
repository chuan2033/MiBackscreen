package hook.HyperBackscreen.hook.theme;

import static hook.HyperBackscreen.hook.util.HookReflection.MAX_EDIT_CONFIG_BYTES;
import static hook.HyperBackscreen.hook.util.HookReflection.asString;
import static hook.HyperBackscreen.hook.util.HookReflection.callMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.callNoArgMethodQuietly;
import static hook.HyperBackscreen.hook.util.HookReflection.callRearListGetter;
import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findStaticCompanionField;
import static hook.HyperBackscreen.hook.util.HookReflection.getFieldValue;
import static hook.HyperBackscreen.hook.util.HookReflection.getStaticStringField;
import static hook.HyperBackscreen.hook.util.HookReflection.grantReadAccess;
import static hook.HyperBackscreen.hook.util.HookReflection.grantThemeResourceAssetAccess;
import static hook.HyperBackscreen.hook.util.HookReflection.grantThemeResourceDirectoryAccess;
import static hook.HyperBackscreen.hook.util.HookReflection.isEmpty;
import static hook.HyperBackscreen.hook.util.HookReflection.readUtf8;
import static hook.HyperBackscreen.hook.util.HookReflection.safeCopy;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.ThemeResourceAccess;
import hook.HyperBackscreen.hook.ModuleMain;
import hook.HyperBackscreen.hook.appindex.SubscreenAppIndex;

/**
 * 主题商店进程里背屏壁纸应用流程的资源修复：应用结果协程入口的 Hook，以及它驱动的
 * 重新应用时 position 提升、快照路径补全、rights / mtz 路径修正、theme_magic 资源引用改写与授权、
 * 背屏运行目录读权限、AI 伙伴（AiMate）快照修复。
 */
public final class ThemeResourceRepair {
    private final ModuleMain module;

    private final Set<String> pendingRepairs = new HashSet<>();
    private final ScheduledThreadPoolExecutor repairExecutor = createRepairExecutor();

    public ThemeResourceRepair(@NonNull ModuleMain module) {
        this.module = module;
    }

    private static ScheduledThreadPoolExecutor createRepairExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "MiBackscreen-Repair");
            thread.setDaemon(true);
            return thread;
        });
        executor.setKeepAliveTime(30, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public void installRearScreenApplyFixHook(@NonNull ClassLoader classLoader) {
        Class<?> applyResultClass = findClass(Constants.THEME_APPLY_RESULT_CLASS, classLoader);
        if (applyResultClass == null) {
            return;
        }

        module.hookMethodIfPresent(
                applyResultClass,
                Constants.THEME_APPLY_RESULT_METHOD,
                new Class[]{Object.class},
                Constants.THEME_APPLY_RESULT_CLASS + "#" + Constants.THEME_APPLY_RESULT_METHOD,
                chain -> {
                    // invokeSuspend 是协程状态机入口，每过一个挂起点都会再进一次；
                    // 预处理只在首次进入（label == 0）时做，避免中途改路径、重复拷文件。
                    Object label = getFieldValue(chain.getThisObject(), "label");
                    if (label instanceof Integer && (Integer) label != 0) {
                        Object resumed = chain.proceed();
                        // 壁纸文件在后续挂起点之后才复制进运行目录，放开读权限这一步每次恢复都要兜。
                        if (PrefsBridge.shouldFixRearScreenApply(module)) grantRearScreenRuntimeAccess();
                        return resumed;
                    }
                    boolean fixApply = PrefsBridge.shouldFixRearScreenApply(module);
                    boolean removeLimit = PrefsBridge.shouldRemoveWallpaperLimit(module);
                    Object bean = fixApply || removeLimit
                            ? getFieldValue(chain.getThisObject(), Constants.THEME_APPLY_BEAN_FIELD)
                            : null;
                    ThemeMagicRepairTarget repairTarget = ThemeMagicRepairTarget.from(bean);
                    if (fixApply) {
                        grantRearScreenRuntimeAccess();
                    }
                    if (bean != null) {
                        if (fixApply) {
                            promoteReappliedWallpaper(bean, classLoader);
                            fillSnapshotPaths(bean);
                            patchRightsPath(bean, classLoader);
                            patchMtzPath(bean);
                        }
                        patchThemeMagicAssetAccess(bean);
                    }
                    Object result = chain.proceed();
                    if (fixApply) {
                        // 壁纸文件是在本流程内才复制进运行目录的，落库后再兜一次。
                        grantRearScreenRuntimeAccess();
                    }
                    if (repairTarget != null) {
                        scheduleThemeMagicAssetRepair(repairTarget);
                    }
                    return result;
                }
        );
    }

    public void repairAiMateSnapshots() {
        repairAiMateSnapshot(
                Constants.AI_MATE_PEEKO_RES_ID,
                Constants.AI_MATE_PEEKO_PREVIEW_PATH);
        repairAiMateSnapshot(
                Constants.AI_MATE_LUMI_RES_ID,
                Constants.AI_MATE_LUMI_PREVIEW_PATH);
    }

    private void repairAiMateSnapshot(
            @NonNull String resId,
            @NonNull String previewPath
    ) {
        try {
            File preview = new File(previewPath);
            if (!preview.isFile() || preview.length() <= 0L) {
                return;
            }
            File root = new File(Constants.THEME_MAGIC_REAR_SCREEN_DIR);
            File[] runtimeDirs = root.listFiles((dir, name) -> name != null && name.startsWith(resId + "_"));
            if (runtimeDirs == null || runtimeDirs.length == 0) {
                return;
            }
            for (File runtimeDir : runtimeDirs) {
                if (runtimeDir == null || !runtimeDir.isDirectory()) continue;
                File snapshot = new File(runtimeDir, "rearScreenScreenshot");
                if (!shouldRepairAiMateSnapshot(snapshot)) {
                    continue;
                }
                if (snapshot.isFile()) {
                    File backup = new File(runtimeDir, "rearScreenScreenshot.mibackscreen.bak");
                    if (!backup.exists()) {
                        safeCopy(module, snapshot, backup);
                    }
                }
                File tmp = new File(runtimeDir, "rearScreenScreenshot.mibackscreen.tmp");
                if (safeCopy(module, preview, tmp) && tmp.renameTo(snapshot)) {
                    grantThemeResourceAssetAccess(snapshot);
                    module.log(Log.INFO, Constants.LOG_TAG,
                            "AI mate snapshot repaired: resId=" + resId);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "repairAiMateSnapshot failed: resId=" + resId, e);
        }
    }

    private static boolean shouldRepairAiMateSnapshot(@NonNull File snapshot) {
        return !snapshot.isFile()
                || snapshot.length() <= 0L
                || snapshot.length() < Constants.AI_MATE_BLANK_SNAPSHOT_MAX_BYTES;
    }

    /**
     * 主题商店重新应用已经存在于“我的背屏”中的壁纸时，只会更新 changed 标记，
     * 不会把该项的 position 移到列表首位。背屏服务能识别 changed，但系统设置页直接
     * 预览 position 最大的第一项，因此会一直显示之前的壁纸。
     *
     * 在宿主原流程写数据库、runtime.json 和 theme_rear_widget 之前提升当前项，保证
     * 三份状态使用同一顺序。只处理已经存在且当前不在首位的 resId + applyId。
     */
    private void promoteReappliedWallpaper(Object bean, ClassLoader classLoader) {
        try {
            Class<?> managerClass = findClass(Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "Theme hook target missing: " + Constants.THEME_REAR_DATA_MANAGER_CLASS);
                return;
            }

            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) {
                throw new NoSuchFieldException(
                        Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            }
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            if (companion == null) return;

            Object manager = callMethod(
                    companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD);
            if (manager == null) return;

            List<?> items = callRearListGetter(manager);
            if (items == null) return;

            String targetResId = (String) callMethod(bean, "getResId");
            String targetApplyId = (String) callMethod(bean, "getApplyId");
            int maxPosition = Integer.MIN_VALUE;
            int currentPosition = Integer.MIN_VALUE;
            boolean existingItem = false;

            for (Object item : items) {
                if (item == null) continue;
                Object positionValue = callMethod(item, "getPosition");
                if (!(positionValue instanceof Number)) continue;
                int position = ((Number) positionValue).intValue();
                maxPosition = Math.max(maxPosition, position);

                String resId = (String) callMethod(item, "getResId");
                String applyId = (String) callMethod(item, "getApplyId");
                if (java.util.Objects.equals(targetResId, resId)
                        && java.util.Objects.equals(targetApplyId, applyId)) {
                    existingItem = true;
                    currentPosition = position;
                }
            }

            if (!existingItem || currentPosition >= maxPosition) return;
            if (maxPosition == Integer.MAX_VALUE) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "Current rear wallpaper not promoted: position overflow");
                return;
            }

            int promotedPosition = maxPosition + 1;
            callMethod(bean, "setPosition", promotedPosition);
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Promoted current rear wallpaper: resId=" + targetResId
                            + ", position=" + currentPosition + "->" + promotedPosition);
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "promoteReappliedWallpaper failed", e);
        }
    }

    private void fillSnapshotPaths(Object bean) {
        try {
            String localPath = (String) callMethod(bean, "getResLocalPath");
            String snapshotPath = (String) callMethod(bean, "getResSnapshotPath");
            if (isEmpty(snapshotPath) && !isEmpty(localPath) && new File(localPath).exists()) {
                callMethod(bean, "setResSnapshotPath", localPath);
            }
            String metaSrc = (String) callMethod(bean, "getMetaPath");
            String metaSnap = (String) callMethod(bean, "getMetaSnapshotPath");
            if (isEmpty(metaSnap) && !isEmpty(metaSrc) && new File(metaSrc).exists()) {
                callMethod(bean, "setMetaSnapshotPath", metaSrc);
            }
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "fillSnapshotPaths failed", e);
        }
    }

    private void patchRightsPath(Object bean, ClassLoader classLoader) {
        try {
            String rightPath = (String) callMethod(bean, "getRightPath");
            if (isEmpty(rightPath) || !rightPath.endsWith(".mra")) {
                return;
            }
            String localPath = (String) callMethod(bean, "getResLocalPath");
            if (isEmpty(localPath) || !localPath.contains("/rearscreen/")) {
                return;
            }

            String rightsDir = resolveRightsDir(classLoader);
            String baseName = new File(rightPath).getName();
            if (!baseName.startsWith("rearscreen_")) {
                baseName = "rearscreen_" + baseName;
            }
            String target = rightsDir + baseName;

            File targetFile = new File(target);
            File sourceFile = new File(rightPath);
            if (targetFile.exists()) {
                // 目标可能是之前借用的「最新授权文件」兜底副本；真正的授权文件到了就换回来。
                if (sourceFile.isFile() && !sourceFile.equals(targetFile)
                        && (sourceFile.length() != targetFile.length()
                        || sourceFile.lastModified() > targetFile.lastModified())) {
                    safeCopy(module, sourceFile, targetFile);
                }
                grantReadAccess(targetFile);
                callMethod(bean, "setRightPath", target);
                return;
            }

            if (!sourceFile.exists()) {
                File fallback = findNewestRightsFile(rightsDir, target);
                if (fallback != null) {
                    safeCopy(module, fallback, targetFile);
                    grantReadAccess(targetFile);
                    callMethod(bean, "setRightPath", target);
                } else {
                    callMethod(bean, "setRightPath", target);
                }
                return;
            }

            if (!targetFile.exists()) {
                safeCopy(module, sourceFile, targetFile);
                grantReadAccess(targetFile);
            }
            callMethod(bean, "setRightPath", target);
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "patchRightsPath failed", e);
        }
    }

    private void patchMtzPath(Object bean) {
        try {
            String srcMtz = (String) callMethod(bean, "getResLocalPath");
            if (isEmpty(srcMtz)) {
                return;
            }
            File srcFile = new File(srcMtz);
            if (!srcFile.exists() || srcFile.isDirectory() || !srcMtz.endsWith(".mrc")) {
                return;
            }
            if (!srcMtz.startsWith("/product/") && !srcMtz.startsWith("/system/") && !srcMtz.startsWith("/vendor/")) {
                return;
            }
            String destMtz = (String) callMethod(bean, "getRuntimeDirWithAuth");
            if (isEmpty(destMtz)) {
                return;
            }
            File destFile = new File(destMtz);
            if (!destFile.exists()) {
                safeCopy(module, srcFile, destFile);
                grantReadAccess(destFile);
            }
            callMethod(bean, "setResLocalPath", destMtz);
            callMethod(bean, "setResSnapshotPath", destMtz);
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "patchMtzPath failed", e);
        }
    }

    private void patchThemeMagicAssetAccess(Object bean) {
        try {
            LinkedHashSet<File> files = new LinkedHashSet<>();
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getResLocalPath")));
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getResSnapshotPath")));
            String editConfigPath = asString(callNoArgMethodQuietly(bean, "getMamlEditConfigPath"));
            addThemeResourceFile(files, editConfigPath);
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getSnapshotPreviewPath")));
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getMetaPath")));
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getMetaSnapshotPath")));

            if (!isEmpty(editConfigPath)) {
                collectThemeMagicFilesFromEditConfig(new File(editConfigPath), files);
            }

            int existingFiles = 0;
            int missingFiles = 0;
            for (File file : files) {
                if (file.exists()) {
                    existingFiles++;
                } else {
                    missingFiles++;
                }
                grantThemeResourceAssetAccess(file);
            }
            if (!files.isEmpty()) {
                module.log(Log.INFO, Constants.LOG_TAG,
                        "Patched rear screen theme resources: total=" + files.size()
                                + ", existing=" + existingFiles
                                + ", missing=" + missingFiles);
            }
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "patchThemeMagicAssetAccess failed", e);
        }
    }

    private void scheduleThemeMagicAssetRepair(@NonNull ThemeMagicRepairTarget target) {
        if (isEmpty(target.editConfigPath)) return;
        synchronized (pendingRepairs) {
            if (pendingRepairs.contains(target.editConfigPath)) return;
            if (pendingRepairs.size() >= 16) {
                module.log(Log.WARN, Constants.LOG_TAG, "Theme repair queue full");
                return;
            }
            pendingRepairs.add(target.editConfigPath);
        }
        scheduleThemeRepairAttempt(target, 0);
    }

    private void scheduleThemeRepairAttempt(ThemeMagicRepairTarget target, int attempt) {
        long[] delays = {0L, 300L, 1000L, 2500L};
        repairExecutor.schedule(() -> {
            boolean enabled = PrefsBridge.shouldFixRearScreenApply(module);
            try {
                if (enabled) {
                    repairThemeMagicAssets(target);
                    grantRearScreenRuntimeAccess();
                }
            } catch (Throwable error) {
                module.log(Log.WARN, Constants.LOG_TAG, "Theme repair failed", error);
            } finally {
                if (enabled && attempt + 1 < delays.length) {
                    scheduleThemeRepairAttempt(target, attempt + 1);
                } else {
                    synchronized (pendingRepairs) { pendingRepairs.remove(target.editConfigPath); }
                }
            }
        }, delays[attempt], TimeUnit.MILLISECONDS);
    }

    private void repairThemeMagicAssets(@NonNull ThemeMagicRepairTarget target) {
        if (isEmpty(target.editConfigPath)) {
            return;
        }
        File editConfig = new File(target.editConfigPath);
        if (!editConfig.exists() || !editConfig.isFile()) {
            return;
        }
        long length = editConfig.length();
        if (length < 0 || length > MAX_EDIT_CONFIG_BYTES) {
            return;
        }
        try {
            String raw = readUtf8(editConfig).trim();
            if (raw.isEmpty()) {
                return;
            }
            Object root = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            RepairStats stats = new RepairStats();
            boolean changed = rewriteThemeMagicFileReferences(root, target, stats);
            if (changed) {
                byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
                // 先写临时文件再 rename：写到一半被杀、或宿主恰好在读时，原配置不会被截断。
                File tmp = SubscreenAppIndex.uniqueTempFile(editConfig);
                try {
                    try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                        out.write(bytes);
                        out.getFD().sync();
                    }
                    if (!tmp.renameTo(editConfig)) {
                        throw new IOException("Failed to replace " + editConfig.getAbsolutePath());
                    }
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                grantThemeResourceAssetAccess(editConfig);
                module.log(Log.INFO, Constants.LOG_TAG,
                        "Rewrote rear screen theme_magic asset references: copied="
                                + stats.copied + ", missing=" + stats.missing);
            }
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "repairThemeMagicAssets failed", e);
        }
    }

    private boolean rewriteThemeMagicFileReferences(
            @Nullable Object node,
            @NonNull ThemeMagicRepairTarget target,
            @NonNull RepairStats stats
    ) throws Throwable {
        boolean changed = false;
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (value instanceof String) {
                    String rewritten = rewriteThemeMagicFileReference((String) value, target, stats);
                    if (!((String) value).equals(rewritten)) {
                        object.put(key, rewritten);
                        changed = true;
                    }
                } else if (rewriteThemeMagicFileReferences(value, target, stats)) {
                    changed = true;
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    String rewritten = rewriteThemeMagicFileReference((String) value, target, stats);
                    if (!((String) value).equals(rewritten)) {
                        array.put(i, rewritten);
                        changed = true;
                    }
                } else if (rewriteThemeMagicFileReferences(value, target, stats)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    @NonNull
    private String rewriteThemeMagicFileReference(
            @NonNull String path,
            @NonNull ThemeMagicRepairTarget target,
            @NonNull RepairStats stats
    ) {
        if (!ThemeResourceAccess.isThemeMagicUserPath(path)) {
            return path;
        }
        File source = new File(path);
        if (!source.exists() || !source.isFile()) {
            stats.missing++;
            return path;
        }
        File destination = resolveRuntimeAssetPath(source, target);
        if (!destination.exists() || destination.length() != source.length()) {
            if (!safeCopy(module, source, destination)) {
                return path;
            }
        }
        grantThemeResourceAssetAccess(destination);
        stats.copied++;
        return destination.getAbsolutePath();
    }

    @NonNull
    private File resolveRuntimeAssetPath(
            @NonNull File source,
            @NonNull ThemeMagicRepairTarget target
    ) {
        String resId = sanitizeFilePart(target.resId, "unknown");
        String applyId = sanitizeFilePart(target.applyId, "current");
        String name = sanitizeFilePart(source.getName(), "asset");
        return new File(
                ThemeResourceAccess.REAR_SCREEN_RUNTIME_DIR,
                "mibackscreen_" + resId + "_" + applyId + "_" + name);
    }

    private void collectThemeMagicFilesFromEditConfig(@NonNull File editConfig, @NonNull Set<File> files) {
        if (!editConfig.exists() || !editConfig.isFile()) {
            return;
        }
        long length = editConfig.length();
        if (length < 0 || length > MAX_EDIT_CONFIG_BYTES) {
            return;
        }
        try {
            String raw = readUtf8(editConfig).trim();
            if (raw.isEmpty()) {
                return;
            }
            Object root = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            collectThemeMagicFilesFromJson(root, files);
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Failed to parse rear screen editConfig: " + editConfig.getAbsolutePath(), e);
        }
    }

    private void collectThemeMagicFilesFromJson(@Nullable Object node, @NonNull Set<File> files) {
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object value = object.opt(keys.next());
                if (value instanceof String) {
                    addThemeResourceFile(files, (String) value);
                } else {
                    collectThemeMagicFilesFromJson(value, files);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    addThemeResourceFile(files, (String) value);
                } else {
                    collectThemeMagicFilesFromJson(value, files);
                }
            }
        }
    }

    private void addThemeResourceFile(@NonNull Set<File> files, @Nullable String path) {
        if (isEmpty(path) || !ThemeResourceAccess.isGrantablePath(path)) {
            return;
        }
        files.add(new File(path));
    }

    /**
     * 保证背屏运行目录可被背屏中心读取：目录 other 需 r-x，目录内 rearscreen_*.mrc 需 other r。
     * 背屏中心按普通应用 uid 运行、不是该目录属主；目录一旦停在 700，它就读不到壁纸文件，
     * 会把收到的 widget 全部判为无效并回退系统默认壁纸（表现为"应用成功但界面没变"）。
     * 只在主题商店进程调用——它才是 /data/system/theme 的属主，其它进程改不动。
     */
    public void grantRearScreenRuntimeAccess() {
        try {
            File dir = new File(ThemeResourceAccess.REAR_SCREEN_RUNTIME_DIR);
            if (!dir.isDirectory()) {
                return;
            }
            grantThemeResourceDirectoryAccess(dir);
            File[] children = dir.listFiles();
            int grantedFiles = 0;
            if (children != null) {
                for (File child : children) {
                    if (child == null || !child.isFile()) continue;
                    String name = child.getName();
                    if (!name.startsWith("rearscreen_") || !name.endsWith(".mrc")) continue;
                    grantReadAccess(child);
                    grantedFiles++;
                }
            }
            module.log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear screen runtime access ensured: dir=" + dir.getName()
                            + ", files=" + grantedFiles);
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "grantRearScreenRuntimeAccess failed", e);
        }
    }

    private String resolveRightsDir(ClassLoader classLoader) {
        String[] fieldNames = {
                Constants.THEME_RIGHTS_DIR_FIELD_OS4,
                Constants.THEME_RIGHTS_DIR_FIELD_PRIMARY,
                Constants.THEME_RIGHTS_DIR_FIELD_FALLBACK
        };
        for (String fieldName : fieldNames) {
            String dir = getStaticStringField(
                    classLoader, Constants.THEME_RESOURCE_CONSTANTS_CLASS, fieldName);
            if (isValidRightsDir(dir)) {
                return dir.endsWith("/") ? dir : dir + "/";
            }
            if (!isEmpty(dir)) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "Ignored invalid theme rights dir from " + fieldName + ": " + dir);
            }
        }
        return Constants.THEME_RIGHTS_DIR_DEFAULT;
    }

    private static boolean isValidRightsDir(@Nullable String dir) {
        if (isEmpty(dir) || !dir.startsWith("/")) return false;
        String normalized = dir.endsWith("/") ? dir : dir + "/";
        return normalized.contains("/theme/rights/");
    }

    private File findNewestRightsFile(String dirPath, String excludePath) {
        File dir = new File(dirPath);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File newest = null;
        long newestTime = Long.MIN_VALUE;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.startsWith("rearscreen_") || !name.endsWith(".mra")) continue;
            if (excludePath != null && excludePath.equals(f.getAbsolutePath())) continue;
            if (f.lastModified() > newestTime) {
                newest = f;
                newestTime = f.lastModified();
            }
        }
        return newest;
    }

    private static final class ThemeMagicRepairTarget {
        @Nullable
        final String resId;
        @Nullable
        final String applyId;
        @Nullable
        final String editConfigPath;

        private ThemeMagicRepairTarget(
                @Nullable String resId,
                @Nullable String applyId,
                @Nullable String editConfigPath
        ) {
            this.resId = resId;
            this.applyId = applyId;
            this.editConfigPath = editConfigPath;
        }

        @Nullable
        static ThemeMagicRepairTarget from(@Nullable Object bean) {
            if (bean == null) return null;
            String editConfigPath = asString(callNoArgMethodQuietly(bean, "getMamlEditConfigPath"));
            if (isEmpty(editConfigPath) || !ThemeResourceAccess.isThemeMagicUserPath(editConfigPath)) {
                return null;
            }
            return new ThemeMagicRepairTarget(
                    asString(callNoArgMethodQuietly(bean, "getResId")),
                    asString(callNoArgMethodQuietly(bean, "getApplyId")),
                    editConfigPath);
        }
    }

    private static final class RepairStats {
        int copied;
        int missing;
    }

    @NonNull
    private static String sanitizeFilePart(@Nullable String value, @NonNull String fallback) {
        if (isEmpty(value)) {
            return fallback;
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isEmpty() ? fallback : sanitized;
    }
}
