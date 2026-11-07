package hook.HyperBackscreen.hook.subscreen;

import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredConstructor;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.getFieldValue;

import android.content.Context;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 背屏中心里 MAML 视频壁纸的两项增强。全部挂在背屏中心自带、未混淆的 {@code com.miui.maml.*} 上，不需要 DexKit。
 *
 * <p><b>续播</b>（{@code rear_video_resume}）。从头播的根因是熄屏 / 被别的背屏 Activity 盖住时
 * TextureView 的 SurfaceTexture 被销毁，亮屏后 {@code openVideo()} 新建 MediaPlayer，{@code mSeekWhenPrepared}
 * 为 0。这里只改「重开播放器时从哪里开始」，不主动开播（是否播放仍由 {@code mTargetState} 与模板决定）：
 * <ol>
 *   <li>R1 {@code releaseMedia(boolean)} 前：播放器有效时按「该 View 上次打开的路径 + 时长」记进度快照
 *       （不用此刻的 {@code mPath}：{@code setVideoPath(新路径)} 先改 mPath 再释放旧播放器）。</li>
 *   <li>R2 {@code openVideo()} 后：按路径取回快照，写进 {@code mSeekWhenPrepared}，由宿主 prepare 后自己 seek。</li>
 *   <li>R3 prepared 回调后：核对真实时长，对上就补一次 {@code SEEK_CLOSEST} 精确 seek
 *       （宿主用的 CLOSEST_SYNC 只落到关键帧），对不上就撤回到 0。</li>
 *   <li>R4/R5 外部命令 {@code resume} / {@code exitAod} 执行期间，吞掉「签名类」模板显式下发的 {@code seekTo 0}。</li>
 *   <li>R6 换壁纸（新建 MamlView 的背屏壁纸路径变了）清空快照表。</li>
 * </ol>
 *
 * <p><b>保留声音</b>（{@code rear_video_keep_audio}）。{@code mVolume} 默认 0，prepare 时被设成静音。
 * R3 里对背屏壁纸（路径含 {@code /rearScreen/}）的播放器直接 {@code MediaPlayer.setVolume(1, 1)}。
 * 不调 {@code VideoElement.setVolume}：那会把 {@code mAudioFocusType} 置 1，之后每次 start 都抢占音频焦点，
 * 亮屏就把正在听的音乐打断；直接设播放器音量只跟随媒体音量混音。
 *
 * <p>安装前校验所需字段，缺任何一个就不装该类并记日志；回调里的异常只影响本次调用。
 */
public final class RearVideoHooks {
    private static final String TEXTURE_VIDEO_VIEW = "com.miui.maml.elements.video.TextureVideoView";
    private static final String BASE_VIDEO_VIEW = "com.miui.maml.elements.video.BaseVideoView";
    private static final String VIDEO_ELEMENT = "com.miui.maml.elements.video.VideoElement";
    private static final String COMMAND_TRIGGERS = "com.miui.maml.CommandTriggers";
    private static final String MAML_VIEW = "com.miui.maml.component.MamlView";
    private static final String MAML_MEDIA_DATA_SOURCE = "com.miui.maml.util.MamlMediaDataSource";
    /** 背屏壁纸资源路径的特征段：{@code /data/system/theme/rearScreen/...}。 */
    private static final String REAR_WALLPAPER_SEGMENT = "/rearScreen/";

    /** 当前线程正在执行的 MAML 外部命令（CommandTriggers.onAction 的参数）。 */
    private static final ThreadLocal<String> CURRENT_COMMAND = new ThreadLocal<>();

    private final ModuleMain module;
    private final RearVideoResumePolicy.SnapshotStore snapshots = new RearVideoResumePolicy.SnapshotStore();
    /** 每个播放 View 上次打开的视频路径（R1 用它当快照 key）。 */
    private final Map<View, String> openedPaths = Collections.synchronizedMap(new WeakHashMap<>());
    /** 已写进 mSeekWhenPrepared、等 prepare 完成核对的续播。 */
    private final Map<View, RearVideoResumePolicy.Snapshot> pendingRestores =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** View 是否属于背屏壁纸（保留声音的作用域），算一次缓存。 */
    private final Map<View, Boolean> rearScopeCache = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Class<?>, PlayerFields> playerFields = new HashMap<>();
    private final Set<Class<?>> preparedListenerClasses = Collections.synchronizedSet(new HashSet<>());
    private final Map<Class<?>, Field> listenerOuterFields = Collections.synchronizedMap(new HashMap<>());

    private volatile boolean resumeInstalled;
    private volatile boolean audioInstalled;
    private volatile long lastLifecycleCommandAt = -1L;
    @Nullable private volatile String lastWallpaperPath;

    public RearVideoHooks(@NonNull ModuleMain module) {
        this.module = module;
    }

    public void install(@NonNull ClassLoader classLoader) {
        boolean resume = PrefsBridge.shouldResumeRearVideo(module);
        boolean audio = PrefsBridge.shouldKeepRearVideoAudio(module);
        if (!resume && !audio) return;
        resumeInstalled = resume;
        audioInstalled = audio;
        int installed = 0;
        for (String name : new String[]{TEXTURE_VIDEO_VIEW, BASE_VIDEO_VIEW}) {
            try {
                if (installPlayerHooks(classLoader, name)) installed++;
            } catch (Throwable error) {
                module.log(Log.WARN, Constants.LOG_TAG, "Rear video hooks failed: " + name, error);
            }
        }
        if (installed == 0) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear video hooks skipped: no MAML video view usable");
            resumeInstalled = false;
            audioInstalled = false;
            return;
        }
        if (resume) {
            try {
                installCommandHooks(classLoader);
            } catch (Throwable error) {
                // R4/R5 失败只影响签名类模板的显式归零，R1~R3 照常工作。
                module.log(Log.WARN, Constants.LOG_TAG, "Rear video seek guard failed", error);
            }
            try {
                installWallpaperSwitchHooks(classLoader);
            } catch (Throwable error) {
                module.log(Log.WARN, Constants.LOG_TAG, "Rear video wallpaper switch hook failed", error);
            }
        }
        module.log(Log.INFO, Constants.LOG_TAG,
                "Rear video hooks installed: resume=" + resume + ", keepAudio=" + audio);
    }

    // ---- R1 / R2：释放前记进度、打开后写回 ----

    private boolean installPlayerHooks(@NonNull ClassLoader classLoader, @NonNull String className) {
        Class<?> viewClass = findClass(className, classLoader);
        if (viewClass == null) return false;
        PlayerFields fields = PlayerFields.of(viewClass);
        if (fields == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear video fields missing, skipped: " + className);
            return false;
        }
        synchronized (playerFields) {
            playerFields.put(viewClass, fields);
        }
        boolean release = module.hookMethodIfPresent(fields.releaseMedia, className + "#releaseMedia",
                chain -> {
                    if (resumeOn()) recordSnapshot(chain.getThisObject(), fields);
                    return chain.proceed();
                });
        boolean open = module.hookMethodIfPresent(fields.openVideo, className + "#openVideo",
                chain -> {
                    Object view = chain.getThisObject();
                    boolean ready = fields.isReadyForPlayback(view);
                    Object result = chain.proceed();
                    if (ready && view instanceof View) onVideoOpened((View) view, fields);
                    return result;
                });
        return release && open;
    }

    private void recordSnapshot(@Nullable Object target, @NonNull PlayerFields fields) {
        if (!(target instanceof View view)) return;
        try {
            Object player = fields.mediaPlayer.get(view);
            int state = fields.currentState.getInt(view);
            if (player == null || !RearVideoResumePolicy.isSnapshotState(state)) return;
            String opened = openedPaths.get(view);
            if (opened == null) return;
            int position = (Integer) fields.getCurrentPosition.invoke(view);
            int duration = (Integer) fields.getDuration.invoke(view);
            boolean looping = fields.looping.getBoolean(view);
            int resumeAt = RearVideoResumePolicy.resumeTarget(position, duration, looping,
                    state == RearVideoResumePolicy.STATE_PLAYBACK_COMPLETED);
            snapshots.put(new RearVideoResumePolicy.Snapshot(opened, duration, resumeAt));
        } catch (Throwable error) {
            module.log(Log.DEBUG, Constants.LOG_TAG, "Rear video snapshot failed: " + error);
        }
    }

    private void onVideoOpened(@NonNull View view, @NonNull PlayerFields fields) {
        try {
            String path = fields.currentPath(view);
            if (path == null) {
                openedPaths.remove(view);
            } else {
                openedPaths.put(view, path);
            }
            if (resumeOn() && path != null) {
                RearVideoResumePolicy.Snapshot snapshot = snapshots.takeLatestForPath(path);
                if (snapshot != null && snapshot.targetMs > 0) {
                    fields.seekWhenPrepared.setInt(view, snapshot.targetMs);
                    pendingRestores.put(view, snapshot);
                } else {
                    pendingRestores.remove(view);
                }
            } else {
                pendingRestores.remove(view);
            }
            ensurePreparedHook(fields.preparedListener.get(view));
        } catch (Throwable error) {
            module.log(Log.DEBUG, Constants.LOG_TAG, "Rear video open hook failed: " + error);
        }
    }

    // ---- R3：prepare 完成后补精确 seek、按需取消静音 ----

    /** 匿名监听类的序号随宿主版本变，运行时从 mPreparedListener 的实际值取类再挂。 */
    private void ensurePreparedHook(@Nullable Object listener) {
        if (listener == null) return;
        Class<?> listenerClass = listener.getClass();
        if (!preparedListenerClasses.add(listenerClass)) return;
        Method onPrepared = findDeclaredMethod(listenerClass, "onPrepared", MediaPlayer.class);
        Field outer = outerViewField(listenerClass);
        if (onPrepared == null || outer == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "Rear video prepared listener unsupported: " + listenerClass.getName());
            return;
        }
        listenerOuterFields.put(listenerClass, outer);
        module.hookMethodIfPresent(onPrepared, listenerClass.getName() + "#onPrepared",
                chain -> {
                    Object result = chain.proceed();
                    Object player = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (player instanceof MediaPlayer mp) {
                        Object view = outer.get(chain.getThisObject());
                        if (view instanceof View) onPrepared((View) view, mp);
                    }
                    return result;
                });
    }

    @Nullable
    private static Field outerViewField(@NonNull Class<?> listenerClass) {
        for (Field field : listenerClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (View.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private void onPrepared(@NonNull View view, @NonNull MediaPlayer player) {
        RearVideoResumePolicy.Snapshot pending = pendingRestores.remove(view);
        if (pending != null) {
            try {
                int actual = player.getDuration();
                if (RearVideoResumePolicy.durationMatches(pending.durationMs, actual)) {
                    player.seekTo(pending.targetMs, MediaPlayer.SEEK_CLOSEST);
                    module.log(Log.DEBUG, Constants.LOG_TAG, "Rear video resumed at " + pending.targetMs + "ms");
                } else {
                    // 同一路径换了内容：撤回宿主按快照做的 seek，从头播。
                    player.seekTo(0L, MediaPlayer.SEEK_CLOSEST);
                    module.log(Log.DEBUG, Constants.LOG_TAG,
                            "Rear video resume dropped: duration " + pending.durationMs + " -> " + actual);
                }
            } catch (Throwable error) {
                module.log(Log.DEBUG, Constants.LOG_TAG, "Rear video precise seek failed: " + error);
            }
        }
        if (audioInstalled && PrefsBridge.shouldKeepRearVideoAudio(module) && isRearWallpaper(view)) {
            try {
                PlayerFields fields = fieldsFor(view.getClass());
                float volume = fields == null || fields.volume == null ? 0f : fields.volume.getFloat(view);
                // 模板自己设过非零音量（VideoCommand setVolume）就以模板为准。
                if (volume == 0f) player.setVolume(1f, 1f);
            } catch (Throwable error) {
                module.log(Log.DEBUG, Constants.LOG_TAG, "Rear video unmute failed: " + error);
            }
        }
    }

    /**
     * 背屏壁纸作用域：视频路径本身在 /rearScreen/ 下（模块落盘的自定义视频），或者它所在 MAML 的资源包在
     * /rearScreen/ 下（壁纸 .mrc 解出来的临时文件路径不含特征段，要回溯到 ResourceLoader）。
     * 背屏中心里的其它 MAML（音乐卡片、智能助理卡片）不算。
     */
    private boolean isRearWallpaper(@NonNull View view) {
        Boolean cached = rearScopeCache.get(view);
        if (cached != null) return cached;
        boolean rear = false;
        try {
            PlayerFields fields = fieldsFor(view.getClass());
            String path = fields == null ? null : fields.currentPath(view);
            if (path != null && path.contains(REAR_WALLPAPER_SEGMENT)) {
                rear = true;
            } else {
                String resourcePath = resourcePathOf(view, fields);
                rear = resourcePath != null && resourcePath.contains(REAR_WALLPAPER_SEGMENT);
            }
        } catch (Throwable ignored) {
            rear = false;
        }
        rearScopeCache.put(view, rear);
        return rear;
    }

    /** View.mVideoListener(VideoElement$1).this$0 → getRoot().getContext().mResourceManager.getResourceLoader().getResourcePath()。 */
    @Nullable
    private static String resourcePathOf(@NonNull View view, @Nullable PlayerFields fields) throws Throwable {
        if (fields == null || fields.videoListener == null) return null;
        Object listener = fields.videoListener.get(view);
        if (listener == null) return null;
        Object element = null;
        for (Field field : listener.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals(VIDEO_ELEMENT)) {
                field.setAccessible(true);
                element = field.get(listener);
                break;
            }
        }
        if (element == null) return null;
        Object root = element.getClass().getMethod("getRoot").invoke(element);
        if (root == null) return null;
        Object screenContext = root.getClass().getMethod("getContext").invoke(root);
        if (screenContext == null) return null;
        Object manager = getFieldValue(screenContext, "mResourceManager");
        if (manager == null) return null;
        Object loader = manager.getClass().getMethod("getResourceLoader").invoke(manager);
        if (loader == null) return null;
        Object resourcePath = loader.getClass().getMethod("getResourcePath").invoke(loader);
        return resourcePath instanceof String ? (String) resourcePath : null;
    }

    // ---- R4 / R5：resume / exitAod 期间吞掉模板的 seekTo 0 ----

    private void installCommandHooks(@NonNull ClassLoader classLoader) {
        Class<?> triggers = findClass(COMMAND_TRIGGERS, classLoader);
        if (triggers != null) {
            module.hookMethodIfPresent(findDeclaredMethod(triggers, "onAction", String.class),
                    COMMAND_TRIGGERS + "#onAction",
                    chain -> {
                        Object action = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        String previous = CURRENT_COMMAND.get();
                        CURRENT_COMMAND.set(action instanceof String ? (String) action : null);
                        try {
                            return chain.proceed();
                        } finally {
                            if (previous == null) CURRENT_COMMAND.remove();
                            else CURRENT_COMMAND.set(previous);
                        }
                    });
        }
        Class<?> mamlView = findClass(MAML_VIEW, classLoader);
        if (mamlView != null) {
            module.hookMethodIfPresent(findDeclaredMethod(mamlView, "sendCommand", String.class),
                    MAML_VIEW + "#sendCommand",
                    chain -> {
                        Object command = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        if (RearVideoResumePolicy.isLifecycleCommand(command instanceof String ? (String) command : null)) {
                            lastLifecycleCommandAt = SystemClock.elapsedRealtime();
                        }
                        return chain.proceed();
                    });
        }
        Class<?> element = findClass(VIDEO_ELEMENT, classLoader);
        Field holderField = null;
        if (element != null) {
            try {
                holderField = element.getDeclaredField("mView");
                holderField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
                holderField = null;
            }
        }
        if (element == null || holderField == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear video seek guard skipped: VideoElement.mView missing");
            return;
        }
        final Field holder = holderField;
        module.hookMethodIfPresent(findDeclaredMethod(element, "seekTo", int.class),
                VIDEO_ELEMENT + "#seekTo",
                chain -> {
                    Object requested = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!(requested instanceof Integer) || (Integer) requested != 0 || !resumeOn()) {
                        return chain.proceed();
                    }
                    Object view = holder.get(chain.getThisObject());
                    if (!(view instanceof View videoView)) return chain.proceed();
                    long last = lastLifecycleCommandAt;
                    long since = last < 0 ? -1L : SystemClock.elapsedRealtime() - last;
                    if (RearVideoResumePolicy.shouldSwallowSeekZero(0, CURRENT_COMMAND.get(), since,
                            pendingRestores.containsKey(videoView), isPausedMidway(videoView))) {
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear video template seekTo 0 skipped during " + CURRENT_COMMAND.get());
                        return null;
                    }
                    return chain.proceed();
                });
    }

    private boolean isPausedMidway(@NonNull View view) {
        PlayerFields fields = fieldsFor(view.getClass());
        if (fields == null) return false;
        try {
            if (fields.mediaPlayer.get(view) == null) return false;
            if (fields.currentState.getInt(view) != RearVideoResumePolicy.STATE_PAUSED) return false;
            int position = (Integer) fields.getCurrentPosition.invoke(view);
            return position > RearVideoResumePolicy.MIN_SEEK_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- R6：换壁纸清快照 ----

    private void installWallpaperSwitchHooks(@NonNull ClassLoader classLoader) {
        Class<?> mamlView = findClass(MAML_VIEW, classLoader);
        if (mamlView == null) return;
        Constructor<?>[] constructors = {
                findDeclaredConstructor(mamlView, Context.class, String.class, String.class, int.class, boolean.class),
                findDeclaredConstructor(mamlView, Context.class, String.class, String.class, int.class,
                        boolean.class, String.class),
        };
        for (Constructor<?> constructor : constructors) {
            if (constructor == null) continue;
            module.hookConstructorIfPresent(constructor, MAML_VIEW + "#<init>" + constructor.getParameterCount(),
                    chain -> {
                        Object result = chain.proceed();
                        Object path = chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null;
                        if (path instanceof String) onMamlCreated((String) path);
                        return result;
                    });
        }
    }

    private void onMamlCreated(@NonNull String path) {
        if (!path.contains(REAR_WALLPAPER_SEGMENT)) return;
        String previous = lastWallpaperPath;
        lastWallpaperPath = path;
        // 同一张壁纸重建（Launcher 重建、换语言）保留进度；换成另一张才清。
        if (previous != null && !previous.equals(path)) {
            snapshots.clear();
            pendingRestores.clear();
            module.log(Log.DEBUG, Constants.LOG_TAG, "Rear video snapshots cleared on wallpaper switch");
        }
    }

    private boolean resumeOn() {
        return resumeInstalled && PrefsBridge.shouldResumeRearVideo(module);
    }

    @Nullable
    private PlayerFields fieldsFor(@NonNull Class<?> viewClass) {
        synchronized (playerFields) {
            for (Class<?> c = viewClass; c != null && c != View.class; c = c.getSuperclass()) {
                PlayerFields fields = playerFields.get(c);
                if (fields != null) return fields;
            }
        }
        return null;
    }

    /** 一个播放 View 类上要用到的成员；必需项缺一个就返回 null（该类不装）。 */
    private static final class PlayerFields {
        final Field mediaPlayer;
        final Field currentState;
        final Field seekWhenPrepared;
        final Field path;
        final Field looping;
        final Field preparedListener;
        @Nullable final Field dataSource;
        @Nullable final Field volume;
        @Nullable final Field videoListener;
        final Method releaseMedia;
        final Method openVideo;
        final Method getCurrentPosition;
        final Method getDuration;
        @Nullable final Method readyForPlayback;

        private PlayerFields(Field mediaPlayer, Field currentState, Field seekWhenPrepared, Field path,
                             Field looping, Field preparedListener, @Nullable Field dataSource,
                             @Nullable Field volume, @Nullable Field videoListener, Method releaseMedia,
                             Method openVideo, Method getCurrentPosition, Method getDuration,
                             @Nullable Method readyForPlayback) {
            this.mediaPlayer = mediaPlayer;
            this.currentState = currentState;
            this.seekWhenPrepared = seekWhenPrepared;
            this.path = path;
            this.looping = looping;
            this.preparedListener = preparedListener;
            this.dataSource = dataSource;
            this.volume = volume;
            this.videoListener = videoListener;
            this.releaseMedia = releaseMedia;
            this.openVideo = openVideo;
            this.getCurrentPosition = getCurrentPosition;
            this.getDuration = getDuration;
            this.readyForPlayback = readyForPlayback;
        }

        @Nullable
        static PlayerFields of(@NonNull Class<?> viewClass) {
            Field mediaPlayer = field(viewClass, "mMediaPlayer", MediaPlayer.class);
            Field currentState = field(viewClass, "mCurrentState", int.class);
            Field seekWhenPrepared = field(viewClass, "mSeekWhenPrepared", int.class);
            Field path = field(viewClass, "mPath", String.class);
            Field looping = field(viewClass, "mLooping", boolean.class);
            Field preparedListener = field(viewClass, "mPreparedListener", MediaPlayer.OnPreparedListener.class);
            Method releaseMedia = findDeclaredMethod(viewClass, "releaseMedia", boolean.class);
            Method openVideo = findDeclaredMethod(viewClass, "openVideo");
            Method position = findDeclaredMethod(viewClass, "getCurrentPosition");
            Method duration = findDeclaredMethod(viewClass, "getDuration");
            if (mediaPlayer == null || currentState == null || seekWhenPrepared == null || path == null
                    || looping == null || preparedListener == null || releaseMedia == null || openVideo == null
                    || position == null || position.getReturnType() != int.class
                    || duration == null || duration.getReturnType() != int.class) {
                return null;
            }
            Method ready = findDeclaredMethod(viewClass, "isReadyForPlayback");
            if (ready != null && ready.getReturnType() != boolean.class) ready = null;
            return new PlayerFields(mediaPlayer, currentState, seekWhenPrepared, path, looping, preparedListener,
                    field(viewClass, "mDataSource", MediaDataSource.class),
                    field(viewClass, "mVolume", float.class),
                    fieldNamed(viewClass, "mVideoListener"),
                    releaseMedia, openVideo, position, duration, ready);
        }

        @Nullable
        private static Field field(@NonNull Class<?> owner, @NonNull String name, @NonNull Class<?> type) {
            Field field = fieldNamed(owner, name);
            return field != null && field.getType() == type ? field : null;
        }

        @Nullable
        private static Field fieldNamed(@NonNull Class<?> owner, @NonNull String name) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                return null;
            }
        }

        /** 宿主 openVideo 在 Surface 未就绪时直接返回；没有这个方法（BaseVideoView）时当作就绪。 */
        boolean isReadyForPlayback(@Nullable Object view) {
            if (readyForPlayback == null || view == null) return true;
            try {
                return Boolean.TRUE.equals(readyForPlayback.invoke(view));
            } catch (Throwable ignored) {
                return true;
            }
        }

        /** mPath 为空时（MediaDataSource 播放）取 MamlMediaDataSource.getPath()。 */
        @Nullable
        String currentPath(@NonNull Object view) throws IllegalAccessException {
            Object value = path.get(view);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
            if (dataSource == null) return null;
            Object source = dataSource.get(view);
            if (source == null || !source.getClass().getName().equals(MAML_MEDIA_DATA_SOURCE)) return null;
            try {
                Object sourcePath = source.getClass().getMethod("getPath").invoke(source);
                return sourcePath instanceof String && !((String) sourcePath).isEmpty() ? (String) sourcePath : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
