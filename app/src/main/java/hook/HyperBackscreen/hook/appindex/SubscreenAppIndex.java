package hook.HyperBackscreen.hook.appindex;

import static hook.HyperBackscreen.hook.util.HookReflection.MAX_THEME_INDEX_BYTES;
import static hook.HyperBackscreen.hook.util.HookReflection.grantThemeResourceAssetAccess;
import static hook.HyperBackscreen.hook.util.HookReflection.isEmpty;
import static hook.HyperBackscreen.hook.util.HookReflection.readUtf8;
import static hook.HyperBackscreen.hook.util.HookReflection.safeCopy;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.BackgroundTasks;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 背屏应用卡索引 appInfo.json 的读改写：官方快捷面板卡同步、已删除本地应用卡条目清理、
 * 主题商店 AI 生成应用卡同步，以及与附加资源模块共用的跨进程锁目录协议
 * （{@link #APP_INFO_LOCK_DIR}）。
 *
 * <p>背屏中心与主题商店两个进程都会用到；实例由 {@link ModuleMain} 持有，每进程一份。
 */
public final class SubscreenAppIndex {
    private final ModuleMain module;

    private final AtomicBoolean aiIndexSyncRunning = new AtomicBoolean();
    private static final long AI_APP_INDEX_SYNC_DEDUP_WINDOW_MS = 2_000L;
    private volatile long lastAiAppIndexSyncedAtElapsed;
    /** 进程内互斥 appInfo.json 的读改写；跨进程（背屏中心 / 主题商店）再叠一把文件锁。 */
    private static final java.util.concurrent.locks.ReentrantLock APP_INFO_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    public SubscreenAppIndex(@NonNull ModuleMain module) {
        this.module = module;
    }

    public void syncAiGeneratedAppCardsToSubscreenIndex() {
        if (!PrefsBridge.shouldEnable18ProFeatures(module)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastAiAppIndexSyncedAtElapsed < AI_APP_INDEX_SYNC_DEDUP_WINDOW_MS
                || !aiIndexSyncRunning.compareAndSet(false, true)) return;
        try {
            BackgroundTasks.execute(() -> {
                try {
                    syncAiGeneratedAppCardsToSubscreenIndexNow();
                    lastAiAppIndexSyncedAtElapsed = SystemClock.elapsedRealtime();
                } finally {
                    aiIndexSyncRunning.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            aiIndexSyncRunning.set(false);
            module.log(Log.WARN, Constants.LOG_TAG, "AI index sync worker busy", error);
        }
    }

    private void syncAiGeneratedAppCardsToSubscreenIndexNow() {
        withAppInfoLock(() -> {
            syncAiGeneratedAppCardsToSubscreenIndexNowLocked();
            return Boolean.TRUE;
        });
    }

    private void syncAiGeneratedAppCardsToSubscreenIndexNowLocked() {
        if (!PrefsBridge.shouldEnable18ProFeatures(module)) return;
        File runtimeFile = new File(Constants.THEME_AI_APP_RUNTIME_FILE);
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        cleanupDeletedSubscreenAppInfoEntries();
        if (!runtimeFile.isFile()) {
            return;
        }
        try {
            JSONArray runtimeItems = new JSONArray(readUtf8(runtimeFile, MAX_THEME_INDEX_BYTES));
            JSONArray appInfoItems = appInfoFile.isFile()
                    ? new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES))
                    : new JSONArray();
            int[] deletedLocalRemoved = new int[1];
            appInfoItems = withoutDeletedLocalAppInfoItems(appInfoItems, deletedLocalRemoved);

            LinkedHashSet<String> runtimeIds = new LinkedHashSet<>();
            for (int i = 0; i < runtimeItems.length(); i++) {
                JSONObject aiItem = runtimeItems.optJSONObject(i);
                if (aiItem == null) continue;
                String productId = aiItem.optString("productId", "");
                if (!isEmpty(productId)) {
                    runtimeIds.add(productId);
                }
            }

            JSONArray syncedItems = new JSONArray();
            LinkedHashSet<String> existingIds = new LinkedHashSet<>();
            int removed = deletedLocalRemoved[0];
            for (int i = 0; i < appInfoItems.length(); i++) {
                JSONObject item = appInfoItems.optJSONObject(i);
                if (item == null) continue;
                String resId = item.optString("resId", "");
                if (isSyncedAiAppInfo(item) && !runtimeIds.contains(resId)) {
                    removed++;
                    continue;
                }
                syncedItems.put(item);
                if (!isEmpty(resId)) {
                    existingIds.add(resId);
                }
            }

            int added = 0;
            for (int i = 0; i < runtimeItems.length(); i++) {
                JSONObject aiItem = runtimeItems.optJSONObject(i);
                if (aiItem == null) continue;
                String productId = aiItem.optString("productId", "");
                if (isEmpty(productId) || existingIds.contains(productId)) {
                    continue;
                }
                JSONObject appInfoItem = createSubscreenAiAppInfo(aiItem);
                if (appInfoItem == null) {
                    continue;
                }
                syncedItems.put(appInfoItem);
                existingIds.add(productId);
                added++;
            }

            if (!PrefsBridge.shouldEnable18ProFeatures(module)) return;
            if (added <= 0 && removed <= 0) {
                grantAiAppIndexAccess(runtimeFile, appInfoFile, syncedItems);
                return;
            }

            backupFileOnce(appInfoFile);
            writeJsonArray(appInfoFile, syncedItems);
            grantAiAppIndexAccess(runtimeFile, appInfoFile, syncedItems);
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Synced AI app cards to SubScreenCenter index: added=" + added
                            + ", removed=" + removed
                            + ", total=" + syncedItems.length());
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "syncAiGeneratedAppCardsToSubscreenIndex failed", e);
        }
    }

    private boolean isSyncedAiAppInfo(@NonNull JSONObject item) {
        String mtzPath = item.optString("mtzPath", "");
        return item.optInt("appCardType", -1) == 2
                && !isEmpty(mtzPath)
                && mtzPath.startsWith(Constants.THEME_MAGIC_AI_APP_DIR + "/");
    }

    public boolean cleanupDeletedSubscreenAppInfoEntries() {
        Boolean result = withAppInfoLock(this::cleanupDeletedSubscreenAppInfoEntriesLocked);
        return Boolean.TRUE.equals(result);
    }

    private boolean cleanupDeletedSubscreenAppInfoEntriesLocked() {
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        if (!appInfoFile.isFile()) return false;
        try {
            JSONArray items = new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES));
            int[] removed = new int[1];
            JSONArray cleaned = withoutDeletedLocalAppInfoItems(items, removed);
            if (removed[0] <= 0) return false;
            backupFileOnce(appInfoFile);
            writeJsonArray(appInfoFile, cleaned);
            grantThemeResourceAssetAccess(appInfoFile);
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Cleaned deleted app cards from SubScreenCenter index: removed="
                            + removed[0] + ", total=" + cleaned.length());
            return true;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "cleanupDeletedSubscreenAppInfoEntries failed", e);
            return false;
        }
    }

    public boolean syncOfficialPanelAppCardWithSubscreenIndex() {
        Boolean result = withAppInfoLock(this::syncOfficialPanelAppCardWithSubscreenIndexLocked);
        return Boolean.TRUE.equals(result);
    }

    private boolean syncOfficialPanelAppCardWithSubscreenIndexLocked() {
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        boolean enabled = PrefsBridge.shouldEnableAppCard(module);
        // 安装请求带一次性编号：宿主进程写不了远程偏好，没法把请求清掉。处理过的编号记在安装标记里，
        // 同一个请求只插入一次 —— 用户之后在背屏编辑里删掉卡片，不会被自动加回来。
        String requestId = PrefsBridge.readAppCardInstallRequestId(module);
        String handledId = readOfficialPanelCardHandledRequest();
        boolean installRequested = PrefsBridge.shouldInstallAppCard(module)
                && (requestId.isEmpty() ? handledId == null : !requestId.equals(handledId));
        JSONObject officialItem = null;
        try {
            JSONArray current = appInfoFile.isFile()
                    ? new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES))
                    : new JSONArray();
            boolean installMarked = isOfficialPanelCardInstallMarked();
            boolean hasOfficialItem = containsOfficialPanelCard(current);
            if (enabled && !hasOfficialItem && !installRequested) {
                grantThemeResourceAssetAccess(appInfoFile);
                return false;
            }
            if (enabled) {
                if (!PanelAppCard.ensureOfficialResources()) {
                    module.log(Log.WARN, Constants.LOG_TAG,
                            "Official quick panel card resources unavailable");
                    return false;
                }
                officialItem = PanelAppCard.createOfficialAppInfo();
                if (officialItem == null) return false;
            }
            JSONArray updated = new JSONArray();
            boolean found = false;
            boolean changed = false;
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null) continue;
                if (!PanelAppCard.isModuleCardInfo(item)) {
                    updated.put(item);
                    continue;
                }
                changed = true;
                if (enabled && !found) {
                    updated.put(officialItem);
                    found = true;
                    changed = !jsonObjectsEqual(item, officialItem);
                }
            }
            if (enabled && !found && installRequested) {
                JSONArray withOfficialFirst = new JSONArray();
                withOfficialFirst.put(officialItem);
                for (int i = 0; i < updated.length(); i++) {
                    withOfficialFirst.put(updated.opt(i));
                }
                updated = withOfficialFirst;
                changed = true;
            }
            if (!changed) {
                if (enabled) {
                    if (found && (!installMarked || installRequested)) {
                        markOfficialPanelCardInstalled(requestId);
                    }
                    grantOfficialPanelCardAccess();
                } else if (installMarked) {
                    clearOfficialPanelCardInstallMark();
                }
                return false;
            }
            backupFileOnce(appInfoFile);
            writeJsonArray(appInfoFile, updated);
            if (enabled && updatedContainsOfficialPanelCard(updated)) {
                markOfficialPanelCardInstalled(requestId);
            } else if (!enabled) {
                clearOfficialPanelCardInstallMark();
            }
            grantOfficialPanelCardAccess();
            module.log(Log.INFO, Constants.LOG_TAG,
                    "Synced official quick panel app card: enabled=" + enabled
                            + ", total=" + updated.length());
            return true;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "syncOfficialPanelAppCardWithSubscreenIndex failed", e);
            return false;
        }
    }

    private boolean containsOfficialPanelCard(@NonNull JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && PanelAppCard.isModuleCardInfo(item)) return true;
        }
        return false;
    }

    private boolean updatedContainsOfficialPanelCard(@NonNull JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && PanelAppCard.isModuleCardInfo(item)) return true;
        }
        return false;
    }

    private boolean isOfficialPanelCardInstallMarked() {
        return new File(Constants.MODULE_PANEL_CARD_INSTALL_MARK).isFile();
    }

    /** 安装标记里记的是已处理的安装请求编号；旧版本写的是 "1"。没有标记返回 null。 */
    @Nullable
    private String readOfficialPanelCardHandledRequest() {
        File mark = new File(Constants.MODULE_PANEL_CARD_INSTALL_MARK);
        if (!mark.isFile()) return null;
        try {
            return readUtf8(mark, 256).trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private void markOfficialPanelCardInstalled(@NonNull String requestId) {
        File mark = new File(Constants.MODULE_PANEL_CARD_INSTALL_MARK);
        File parent = mark.getParentFile();
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(mark, false)) {
                out.write((requestId.isEmpty() ? "1" : requestId).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            grantThemeResourceAssetAccess(mark);
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "markOfficialPanelCardInstalled failed", e);
        }
    }

    private void clearOfficialPanelCardInstallMark() {
        File mark = new File(Constants.MODULE_PANEL_CARD_INSTALL_MARK);
        if (mark.isFile() && !mark.delete()) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Failed to clear official panel card install mark");
        }
    }

    private boolean jsonObjectsEqual(@Nullable JSONObject a, @Nullable JSONObject b) {
        return a != null && b != null && a.toString().equals(b.toString());
    }

    private void grantOfficialPanelCardAccess() {
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_ROOT));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_RESOURCE));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_DESCRIPTION));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_META));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_ICON));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_PREVIEW_LIGHT));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_PREVIEW_DARK));
        grantThemeResourceAssetAccess(new File(Constants.SUBSCREEN_APP_INFO_FILE));
    }

    @NonNull
    private JSONArray withoutDeletedLocalAppInfoItems(
            @NonNull JSONArray items,
            @NonNull int[] removed
    ) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (isDeletedLocalAppInfo(item)) {
                removed[0]++;
                continue;
            }
            kept.put(item);
        }
        return kept;
    }

    private boolean isDeletedLocalAppInfo(@NonNull JSONObject item) {
        String mtzPath = item.optString("mtzPath", "");
        if (!isLocalAppCardPath(mtzPath)) return false;
        String iconPath = item.optString("appIconPath", "");
        String previewLightPath = item.optString("previewLightPath", "");
        String previewDarkPath = item.optString("previewDarkPath", "");
        return !pathExists(mtzPath)
                || (!isEmpty(iconPath) && !pathExists(iconPath))
                || (!isEmpty(previewLightPath) && !pathExists(previewLightPath))
                || (!isEmpty(previewDarkPath) && !pathExists(previewDarkPath));
    }

    private boolean isLocalAppCardPath(@Nullable String path) {
        return path != null
                && (path.startsWith(Constants.THEME_MAGIC_APP_CARD_DIR + "/")
                || path.startsWith(Constants.THEME_MAGIC_AI_APP_DIR + "/")
                || path.startsWith(Constants.THEME_MAGIC_MIBACKSCREEN_RESOURCE_DIR + "/"));
    }

    private boolean pathExists(@Nullable String path) {
        return !isEmpty(path) && new File(path).exists();
    }

    @Nullable
    private JSONObject createSubscreenAiAppInfo(@NonNull JSONObject aiItem) {
        String productId = aiItem.optString("productId", "");
        String resLocalPath = aiItem.optString("resLocalPath", "");
        if (isEmpty(productId) || isEmpty(resLocalPath)) {
            return null;
        }
        String appName = aiItem.optString("resName", productId);
        String appIconPath = aiItem.optString("appPicPath", "");
        String previewPath = aiItem.optString("previewImagePath", "");
        try {
            JSONObject item = new JSONObject();
            item.put("resId", productId);
            item.put("appName", isEmpty(appName) ? productId : appName);
            item.put("mtzPath", resLocalPath);
            if (!isEmpty(appIconPath)) {
                item.put("appIconPath", appIconPath);
            }
            if (!isEmpty(previewPath)) {
                item.put("previewLightPath", previewPath);
                item.put("previewDarkPath", previewPath);
            }
            item.put("isGame", aiItem.optBoolean("isGame", false));
            item.put("isPreset", false);
            item.put("appCardType", 2);
            return item;
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "createSubscreenAiAppInfo failed: productId=" + productId, e);
            return null;
        }
    }

    private void grantAiAppIndexAccess(
            @NonNull File runtimeFile,
            @NonNull File appInfoFile,
            @NonNull JSONArray appInfoItems
    ) {
        grantThemeResourceAssetAccess(runtimeFile);
        grantThemeResourceAssetAccess(appInfoFile);
        File aiRoot = new File(Constants.THEME_MAGIC_AI_APP_DIR);
        grantThemeResourceAssetAccess(aiRoot);
        for (int i = 0; i < appInfoItems.length(); i++) {
            JSONObject item = appInfoItems.optJSONObject(i);
            if (item == null || item.optInt("appCardType", 0) != 2) continue;
            grantThemeResourceAssetAccess(new File(item.optString("mtzPath", "")));
            grantThemeResourceAssetAccess(new File(item.optString("appIconPath", "")));
            grantThemeResourceAssetAccess(new File(item.optString("previewLightPath", "")));
            grantThemeResourceAssetAccess(new File(item.optString("previewDarkPath", "")));
        }
    }

    private void backupFileOnce(@NonNull File file) {
        if (!file.isFile()) {
            return;
        }
        File backup = new File(file.getAbsolutePath() + ".mibackscreen.bak");
        if (backup.exists()) {
            return;
        }
        if (safeCopy(module, file, backup)) {
            grantThemeResourceAssetAccess(backup);
        }
    }

    @NonNull
    public static File uniqueTempFile(@NonNull File target) {
        return new File(target.getAbsolutePath() + ".mibackscreen." + android.os.Process.myPid()
                + "." + Thread.currentThread().getId() + ".tmp");
    }

    /**
     * appInfo.json 的跨进程锁目录。和附加资源模块（ksu-module 的 appinfo_txn.sh）共用同一个协议：
     * <ul>
     *   <li>加锁 = {@code mkdir} 这个目录（原子操作，成功即持有）；解锁 = 删掉它；</li>
     *   <li>目录已存在且修改时间超过 {@link #APP_INFO_LOCK_STALE_MS} 视为持有者已死，删掉重抢。</li>
     * </ul>
     * 不用 FileChannel 锁：那是 fcntl 锁，shell 侧的 busybox 拿不到同一把；而且它会无限期阻塞，
     * 这里的调用方可能就在宿主主线程上。
     */
    static final String APP_INFO_LOCK_DIR = Constants.SUBSCREEN_APP_INFO_FILE + ".mibackscreen.lockd";

    private static final long APP_INFO_LOCK_STALE_MS = 30_000L;

    /** 宿主线程上最多等这么久；拿不到就退回只有进程内互斥（与改动前的最坏情况相同）。 */
    private static final long APP_INFO_LOCK_WAIT_MS = 2_000L;

    private static boolean acquireAppInfoDirLock(@NonNull File lockDir) {
        long deadline = SystemClock.uptimeMillis() + APP_INFO_LOCK_WAIT_MS;
        while (true) {
            if (lockDir.mkdir()) return true;
            long modified = lockDir.lastModified();
            if (modified > 0 && System.currentTimeMillis() - modified > APP_INFO_LOCK_STALE_MS) {
                deleteLockDir(lockDir);
                continue;
            }
            if (!lockDir.getParentFile().isDirectory()) return false;
            if (SystemClock.uptimeMillis() >= deadline) return false;
            SystemClock.sleep(50L);
        }
    }

    private static void deleteLockDir(@NonNull File lockDir) {
        File[] children = lockDir.listFiles();
        if (children != null) {
            for (File child : children) {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        lockDir.delete();
    }

    /**
     * 在 appInfo.json 的读改写外面加锁：进程内用可重入锁，跨进程用 {@link #APP_INFO_LOCK_DIR}。
     * 锁目录建不出来（目录权限）或等超时，只保留进程内互斥，不影响原逻辑。
     */
    @Nullable
    private <T> T withAppInfoLock(@NonNull java.util.concurrent.Callable<T> body) {
        if (APP_INFO_LOCK.isHeldByCurrentThread()) {
            try {
                return body.call();
            } catch (Throwable e) {
                module.log(Log.WARN, Constants.LOG_TAG, "app index update failed", e);
                return null;
            }
        }
        APP_INFO_LOCK.lock();
        File lockDir = new File(APP_INFO_LOCK_DIR);
        boolean crossProcess = false;
        try {
            try {
                crossProcess = acquireAppInfoDirLock(lockDir);
                if (!crossProcess) {
                    module.log(Log.DEBUG, Constants.LOG_TAG, "app index cross-process lock unavailable, continuing");
                }
            } catch (Throwable lockError) {
                module.log(Log.DEBUG, Constants.LOG_TAG, "app index cross-process lock failed: " + lockError);
            }
            return body.call();
        } catch (Throwable e) {
            module.log(Log.WARN, Constants.LOG_TAG, "app index update failed", e);
            return null;
        } finally {
            if (crossProcess) deleteLockDir(lockDir);
            APP_INFO_LOCK.unlock();
        }
    }

    private void writeJsonArray(@NonNull File file, @NonNull JSONArray array) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }
        // 临时文件名带进程与线程号：背屏中心和主题商店可能同时写同一个索引，固定名字会互相截断。
        File tmp = uniqueTempFile(file);
        byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(bytes);
            out.getFD().sync();
        }
        grantThemeResourceAssetAccess(tmp);
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Failed to replace file: " + file.getAbsolutePath());
        }
    }
}
