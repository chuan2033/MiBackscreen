package hook.HyperBackscreen.hook.theme;

import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.getStaticFieldValueByType;

import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.hook.ModuleMain;
import hook.HyperBackscreen.hook.target.TargetMembers;
import hook.HyperBackscreen.hook.target.TargetResolver;
import hook.HyperBackscreen.hook.target.ThemeClassTargets;

/**
 * 主题商店里背屏自定义视频的两项增强（都只作用于背屏，锁屏 / 桌面视频壁纸不受影响）：
 *
 * <ul>
 *   <li><b>解除限制</b>（{@code rear_video_unlock_limits}）：
 *     T1 {@code VideoEditorConfig$Builder} 的构建方法（混淆名，按「无参、返回 VideoEditorConfig」唯一定位）
 *     出来的配置，调用栈在 {@code com.rearScreen.} 里（兜底：三个布尔开关全开，主题商店里只有背屏这么配）
 *     就记进「背屏配置」集合；
 *     T2 视频校验协程（DexKit：包 {@code com.personalizedEditor.interceptor} + 字符串
 *     {@code "checkVideo: last frameRate = "}）的结果如果是「进裁剪页」或「进压缩页」且配置属于背屏，
 *     改成 Success 直接用原片。格式不对 / HDR / 杜比（Invalid）仍然拒绝。
 *     T2 定位失败时退到 T2'：构建前把背屏 Builder 的时长上限改成无限、帧率上限改成 240，
 *     分辨率超过 2560x1440 的视频仍会进压缩页。</li>
 *   <li><b>保留音轨</b>（{@code rear_video_keep_audio}）：{@code VideoSplitor}
 *     （DexKit：包 {@code com.android.thememanager.util} + 字符串 {@code "Saw input EOS."}）的
 *     静态分离方法 {@code (File, File, File) -> Pair}，在背屏调用链里、输出名以 {@code _videoSplit} 结尾时，
 *     改成把原文件整份拷过去（音轨跟着保留），返回 {@code Pair(输出, null)}；其他调用照常。</li>
 * </ul>
 *
 * 每个 Hook 只在宿主进程启动时开关已打开才安装；定位失败只记日志、不改任何结果。
 */
public final class RearVideoImportHooks {
    private static final String REAR_CALLER_PREFIX = "com.rearScreen.";
    private static final String VIDEO_SPLIT_SUFFIX = "_videoSplit";
    /** T2' 兜底：帧率上限。背屏 MAML 播放端能硬解 4K60 / 1080p240，240 已覆盖常见录像。 */
    private static final int UNLOCKED_MAX_FPS = 240;

    private final ModuleMain module;
    /** T1 认出来的背屏配置；弱引用，配置对象用完自然回收。 */
    private final Set<Object> rearConfigs =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public RearVideoImportHooks(@NonNull ModuleMain module) {
        this.module = module;
    }

    public void install(@NonNull ClassLoader classLoader) {
        if (PrefsBridge.shouldUnlockRearVideoLimits(module)) {
            try {
                installUnlockLimits(classLoader);
            } catch (Throwable error) {
                module.log(Log.WARN, Constants.LOG_TAG, "Rear video unlock hooks failed", error);
            }
        }
        if (PrefsBridge.shouldKeepRearVideoAudio(module)) {
            try {
                installKeepAudio(classLoader);
            } catch (Throwable error) {
                module.log(Log.WARN, Constants.LOG_TAG, "Rear video keep-audio hook failed", error);
            }
        }
    }

    // ---- 解除时长 / 帧率 / 分辨率限制 ----

    private void installUnlockLimits(@NonNull ClassLoader classLoader) {
        Class<?> builderClass = findClass(ThemeClassTargets.VIDEO_EDITOR_CONFIG_BUILDER_CLASS, classLoader);
        Class<?> configClass = findClass(ThemeClassTargets.VIDEO_EDITOR_CONFIG_CLASS, classLoader);
        Method buildMethod = builderClass == null || configClass == null
                ? null
                : TargetMembers.uniqueNoArgMethod(builderClass, configClass);
        if (buildMethod == null) {
            // 没有 T1 就认不出背屏配置，T2 / T2' 都不能装，否则会误伤锁屏 / 桌面视频壁纸。
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Rear video unlock skipped: VideoEditorConfig$Builder build method not found");
            return;
        }
        BuilderFields builderFields = BuilderFields.of(builderClass);

        DepthCheck depthCheck = DepthCheck.resolve(classLoader, configClass);
        if (depthCheck == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Rear video check coroutine not resolved, fallback to builder limits: "
                            + (builderFields == null ? "unavailable" : "ok"));
        }
        final boolean rewriteBuilder = depthCheck == null && builderFields != null;
        if (depthCheck == null && builderFields == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear video unlock skipped: no usable hook point");
            return;
        }

        module.hookMethodIfPresent(buildMethod,
                ThemeClassTargets.VIDEO_EDITOR_CONFIG_BUILDER_CLASS + "#" + buildMethod.getName(),
                chain -> {
                    Object builder = chain.getThisObject();
                    boolean rear = isRearCaller() || (builderFields != null && builderFields.allFlagsOn(builder));
                    if (rear && rewriteBuilder && PrefsBridge.shouldUnlockRearVideoLimits(module)) {
                        builderFields.unlock(builder);
                    }
                    Object config = chain.proceed();
                    if (rear && config != null) rearConfigs.add(config);
                    return config;
                });

        if (depthCheck == null) return;
        module.hookMethodIfPresent(depthCheck.invokeSuspend,
                depthCheck.owner.getName() + "#invokeSuspend",
                chain -> {
                    Object result = chain.proceed();
                    if (!PrefsBridge.shouldUnlockRearVideoLimits(module)) return result;
                    Object config = depthCheck.configField.get(chain.getThisObject());
                    if (config == null || !rearConfigs.contains(config)) return result;
                    if (depthCheck.isLimitResult(result)) {
                        module.log(Log.INFO, Constants.LOG_TAG,
                                "Rear video check relaxed: " + result.getClass().getSimpleName() + " -> Success");
                        return depthCheck.success;
                    }
                    return result;
                });
        module.log(Log.INFO, Constants.LOG_TAG, "Rear video unlock hooks installed");
    }

    /** Builder 里的上限字段：唯一的 long（最大时长）、唯一的 int（最大帧率）、三个 boolean。 */
    private static final class BuilderFields {
        final Field maxDuration;
        final Field maxFps;
        final List<Field> flags;

        private BuilderFields(Field maxDuration, Field maxFps, List<Field> flags) {
            this.maxDuration = maxDuration;
            this.maxFps = maxFps;
            this.flags = flags;
        }

        @Nullable
        static BuilderFields of(@NonNull Class<?> builderClass) {
            Field longField = TargetMembers.uniqueFieldOfType(builderClass, long.class);
            Field intField = TargetMembers.uniqueFieldOfType(builderClass, int.class);
            List<Field> flags = new ArrayList<>();
            for (Field field : builderClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == boolean.class) {
                    field.setAccessible(true);
                    flags.add(field);
                }
            }
            if (longField == null || intField == null || flags.size() != 3) return null;
            longField.setAccessible(true);
            intField.setAccessible(true);
            return new BuilderFields(longField, intField, flags);
        }

        boolean allFlagsOn(@Nullable Object builder) {
            if (builder == null) return false;
            try {
                for (Field flag : flags) {
                    if (!flag.getBoolean(builder)) return false;
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        void unlock(@NonNull Object builder) throws IllegalAccessException {
            maxDuration.setLong(builder, Long.MAX_VALUE);
            maxFps.setInt(builder, UNLOCKED_MAX_FPS);
        }
    }

    /** T2 所需的全部成员；任何一个拿不到就整体放弃（返回 null）。 */
    private static final class DepthCheck {
        final Class<?> owner;
        final Method invokeSuspend;
        final Field configField;
        final Object success;
        final Class<?> durationCrop;
        final Class<?> compression;

        private DepthCheck(Class<?> owner, Method invokeSuspend, Field configField, Object success,
                           Class<?> durationCrop, Class<?> compression) {
            this.owner = owner;
            this.invokeSuspend = invokeSuspend;
            this.configField = configField;
            this.success = success;
            this.durationCrop = durationCrop;
            this.compression = compression;
        }

        @Nullable
        static DepthCheck resolve(@NonNull ClassLoader classLoader, @NonNull Class<?> configClass) {
            Class<?> owner = TargetResolver.resolveClass(classLoader, ThemeClassTargets.VIDEO_DEPTH_CHECK);
            if (owner == null) return null;
            Method invokeSuspend = findDeclaredMethod(owner, "invokeSuspend", Object.class);
            Field configField = TargetMembers.uniqueFieldOfType(owner, configClass);
            String base = ThemeClassTargets.CHECK_VIDEO_RESULT_CLASS;
            Class<?> successClass = findClass(base + "$Success", classLoader);
            Class<?> durationCrop = findClass(base + "$DurationCrop", classLoader);
            Class<?> compression = findClass(base + "$Compression", classLoader);
            Object success = successClass == null ? null : getStaticFieldValueByType(successClass, successClass);
            if (invokeSuspend == null || configField == null || success == null
                    || durationCrop == null || compression == null) {
                return null;
            }
            configField.setAccessible(true);
            return new DepthCheck(owner, invokeSuspend, configField, success, durationCrop, compression);
        }

        /** 只放开「进裁剪页」与「进压缩页」；Invalid（格式 / HDR / 杜比 / 没有视频轨）保持原样。 */
        boolean isLimitResult(@Nullable Object result) {
            return durationCrop.isInstance(result) || compression.isInstance(result);
        }
    }

    // ---- 保留音轨 ----

    private void installKeepAudio(@NonNull ClassLoader classLoader) {
        Class<?> splitorClass = TargetResolver.resolveClass(classLoader, ThemeClassTargets.VIDEO_SPLITOR);
        Method split = splitorClass == null
                ? null
                : TargetMembers.uniqueMethod(splitorClass, Pair.class, File.class, File.class, File.class);
        if (split == null || !Modifier.isStatic(split.getModifiers())) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear video keep-audio skipped: VideoSplitor not resolved");
            return;
        }
        split.setAccessible(true);
        module.hookMethodIfPresent(split, splitorClass.getName() + "#" + split.getName(),
                chain -> {
                    if (!PrefsBridge.shouldKeepRearVideoAudio(module)) return chain.proceed();
                    List<Object> args = chain.getArgs();
                    Object srcValue = args.isEmpty() ? null : args.get(0);
                    Object dstValue = args.size() > 1 ? args.get(1) : null;
                    if (!(srcValue instanceof File src) || !(dstValue instanceof File dst)
                            || !dst.getName().endsWith(VIDEO_SPLIT_SUFFIX)
                            || !src.isFile() || !isRearCaller()) {
                        return chain.proceed();
                    }
                    if (!copyWhole(src, dst)) return chain.proceed();
                    module.log(Log.INFO, Constants.LOG_TAG,
                            "Rear video kept audio track: " + dst.getName() + " (" + dst.length() + " bytes)");
                    return new Pair<>(dst, null);
                });
        module.log(Log.INFO, Constants.LOG_TAG, "Rear video keep-audio hook installed");
    }

    /** 先写临时文件再改名，中途失败不留下半截文件（返回 false 时由原逻辑接手）。 */
    private boolean copyWhole(@NonNull File src, @NonNull File dst) {
        File tmp = new File(dst.getPath() + ".mibackscreen.tmp");
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.getFD().sync();
            }
            if (dst.exists() && !dst.delete()) return false;
            if (!tmp.renameTo(dst)) return false;
            return true;
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear video copy failed", error);
            return false;
        } finally {
            if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    /** 调用栈里是否有背屏编辑页（com.rearScreen.*）的帧；锁屏 / 桌面编辑器不会经过这些类。 */
    private static boolean isRearCaller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (frame.getClassName().startsWith(REAR_CALLER_PREFIX)) return true;
        }
        return false;
    }
}
