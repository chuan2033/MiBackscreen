package hook.HyperBackscreen.lyric

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.BackgroundTasks
import hook.HyperBackscreen.common.Constants
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 背屏实时歌词（方案 B1）：把 SuperLyric 的当前歌词行写进背屏音乐卡片的歌名行，歌手行改成「歌名 - 歌手」
 * （和播放器「车载 / 蓝牙歌词」同一种排版）。
 *
 * - 只改背屏自带的音乐卡片（卡片 XML 里有 `album_artist_text`），其余 MusicControl（用户主题里的）
 *   只写 `music_control.lyric_current`，让本来就引用歌词变量的主题也能显示；
 * - 条件不满足（见 [RearLyricPolicy.lyricToShow]）时恢复原歌名和歌手名；
 * - 所有 MAML 变量写入和 requestUpdate 都在背屏中心主线程做；SuperLyric 回调在 Binder 线程，先切回来；
 * - 注册 / 注销订阅（Binder 调用）放到后台线程；
 * - 开关由模块 App 发签名权限广播 [Constants.ACTION_SYNC_REAR_LYRIC] 通知，不用重启背屏中心。
 *
 * 任何反射失败都只让歌词不显示，不抛给宿主。
 */
internal object RearLyricHost {
    private const val TAG = Constants.LOG_TAG
    private const val MUSIC_CONTROL_CLASS = "com.miui.maml.elements.MusicControlScreenElement"

    /** 一张音乐卡片的附加状态。不能引用卡片本身（WeakHashMap 的值强引用键会导致泄漏）。 */
    private class Card(val stock: Boolean) {
        /** 宿主最近一次 updateAlbum 写入的歌名 / 歌手名（车载歌词已换回真实值）；恢复时用。 */
        var originalTitle: String? = null
        var originalArtist: String? = null
        /** 当前写在歌名行上的歌词；null 表示歌名行和歌手行都是原样。 */
        var showing: String? = null
        /** 是否由我们写过 lyric_current（没有原生歌词时才写，恢复时清空）。 */
        var wroteLyricVar = false
        /** 调试日志去重：只在判定结果变化时记一条。 */
        var lastTrace: String? = null
        /** 宿主最近一次收到的「不是歌词」的歌名，以及当时的媒体时长（同一首才用）。见 [rewriteAlbum]。 */
        var knownTitle: String? = null
        var knownDuration: Long = -1L
        var lastRestoredTitle: String? = null
    }

    /** 按时间轴驱动时最长多久按进度校正一次（拖动进度条不一定有回调）。 */
    private const val TIMELINE_MAX_DELAY_MS = 1500L
    /** 写回歌手名后等多久再把 enterAod 交给卡片：等歌手行按新文字重新测宽（AOD 动画按它算居中位置）。 */
    private const val ENTER_AOD_DEFER_MS = 80L
    /** 退出 AOD 后歌手行要先播完回位动画（约 0.5s），期间不写歌词，否则歌词按歌手名的居中位置滑回来。 */
    private const val EXIT_AOD_HOLD_MS = 700L

    private val lock = Any()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val cards = WeakHashMap<Any, Card>()
    private var module: XposedModule? = null
    private var source: RearLyricSource? = null
    private var latest: LyricLineUpdate? = null
    private var launcherResumed = false
    private var inAod = false
    private var receiver: BroadcastReceiver? = null
    private var receiverActivity = WeakReference<Activity>(null)
    private var reflection: Reflection? = null
    private var reflectionFailed = false
    /** 退出 AOD 后这个时刻（uptimeMillis）之前仍按息屏处理。 */
    private var aodHoldUntil = 0L
    /** 被推迟的 enterAod：MamlView → 重发动作。只在主线程读写。 */
    private val pendingEnterAod = LinkedHashMap<Any, Runnable>()
    private val flushEnterAod = Runnable { flushPendingEnterAod() }
    private val timelineTick = Runnable {
        tickAt = 0L
        applyAll()
    }
    /** 已排的时间轴刷新时刻（uptimeMillis），0 表示没排。只在主线程读写。 */
    private var tickAt = 0L

    @JvmStatic
    fun install(module: XposedModule) {
        synchronized(lock) {
            this.module = module
            if (source == null) {
                source = RearLyricSource(
                    onLine = { update -> runOnMain { onLine(update) } },
                    onStop = { stop -> runOnMain { onStop(stop) } },
                    log = { message -> log(Log.INFO, message) },
                )
            }
        }
    }

    // ---- 卡片生命周期（由 Hook 调用，可能不在主线程） ----

    @JvmStatic
    fun onCardCreated(element: Any, stock: Boolean) {
        synchronized(lock) { cards[element] = Card(stock) }
        runOnMain { applyCard(element) }
    }

    @JvmStatic
    fun onCardFinished(element: Any) {
        synchronized(lock) { cards.remove(element) }
    }

    /** 可见性 / 播放状态 / 根暂停恢复变化后重新判断。 */
    @JvmStatic
    fun onCardChanged(element: Any) {
        if (!isTracked(element)) return
        runOnMain { applyCard(element) }
    }

    /**
     * updateAlbum 之前：播放器开了车载 / 蓝牙歌词、把元数据标题换成了当前这句时，换回真实歌名和歌手
     * （见 [RearLyricPolicy.restoreCarLyricAlbum]），返回改写后的三个参数；不用改返回 null。
     * 只在歌词开关打开时、只对背屏自带音乐卡片生效。
     */
    @JvmStatic
    fun rewriteAlbum(element: Any, title: Any?, artist: Any?, album: Any?): Array<Any?>? {
        val (card, line) = synchronized(lock) {
            val card = cards[element] ?: return null
            card to latest
        }
        if (!card.stock || !isEnabled()) return null
        val refl = reflection() ?: return null
        return runCatching {
            val original = AlbumText(title as? String, artist as? String, album as? String)
            val duration = refl.duration(element)
            if (!RearLyricPolicy.isLyricTitle(original.title, line)) {
                // 正常的歌名：记下来，车载歌词把标题换掉时用它换回。还没收到歌词时判断不了是不是歌词，
                // 只认车载格式里「歌手 = 歌名-歌手」能对上的标题。
                if (RearLyricPolicy.isTrustedTitle(original, line)) synchronized(lock) {
                    card.knownTitle = original.title
                    card.knownDuration = duration
                }
                return@runCatching null
            }
            val knownTitle = synchronized(lock) {
                // 时长对不上说明已经换歌，记下的是上一首的歌名，不能用。
                if (card.knownDuration != duration) null else card.knownTitle
            }
            val fixed = RearLyricPolicy.restoreCarLyricAlbum(
                original, line, refl.clientPackage(element), knownTitle,
            ) ?: return@runCatching null
            if (hook.HyperBackscreen.BuildConfig.DEBUG && fixed.title != card.lastRestoredTitle) {
                card.lastRestoredTitle = fixed.title
                log(Log.INFO, "lyric album restored: $original -> $fixed")
            }
            arrayOf<Any?>(fixed.title, fixed.artist, fixed.album)
        }.getOrNull()
    }

    /** updateAlbum 之前：记下宿主要写的歌名和歌手名，供恢复用。 */
    @JvmStatic
    fun beforeUpdateAlbum(element: Any, title: Any?, artist: Any?) {
        synchronized(lock) {
            val card = cards[element] ?: return
            card.originalTitle = title as? String
            card.originalArtist = artist as? String
            // 宿主马上会用原值覆盖两行，等 after 里按需再写回歌词。
            card.showing = null
        }
    }

    /** updateAlbum 之后：歌词显示期间宿主刷新元数据不能把歌词冲掉。主线程上同步重写，避免闪一下歌手名。 */
    @JvmStatic
    fun afterUpdateAlbum(element: Any) {
        if (!isTracked(element)) return
        runOnMain { applyCard(element) }
    }

    // ---- 背屏桌面 / 息屏 ----

    @JvmStatic
    fun onLauncherResumed(activity: Activity) {
        ensureReceiver(activity)
        synchronized(lock) { launcherResumed = true }
        if (isEnabled()) startSource()
        // 已订阅但还没拿到歌词（刚订阅、或之前一直在息屏）：补拉一次当前句，不等下一次换句。
        val needPull = synchronized(lock) { latest == null }
        if (needPull) {
            val current = synchronized(lock) { source }
            if (current?.isRegistered == true) runCatching { BackgroundTasks.execute { current.pullLatest() } }
        }
        runOnMain { applyAll() }
    }

    @JvmStatic
    fun onLauncherPaused() {
        synchronized(lock) { launcherResumed = false }
        runOnMain { applyAll() }
    }

    @JvmStatic
    fun onLauncherDestroyed(activity: Activity) {
        synchronized(lock) {
            if (receiverActivity.get() === activity) {
                receiver?.let { runCatching { activity.unregisterReceiver(it) } }
                receiver = null
                receiverActivity = WeakReference(null)
            }
            launcherResumed = false
        }
    }

    @JvmStatic
    fun onAodChanged(aod: Boolean) {
        synchronized(lock) {
            if (inAod == aod) return
            inAod = aod
            aodHoldUntil = 0L
        }
        // AOD 下也显示歌词时退出前后歌手行都是歌词，不用等回位动画。
        val hold = !aod && !isShowInAod()
        if (hold) synchronized(lock) { aodHoldUntil = SystemClock.uptimeMillis() + EXIT_AOD_HOLD_MS }
        runOnMain {
            applyAll()
            if (hold) main.postDelayed({ applyAll() }, EXIT_AOD_HOLD_MS + 20)
        }
    }

    /**
     * `MamlView.sendCommand` 之前调用，返回 true 表示这次命令先不发（稍后由 [resend] 重发）。
     *
     * 自带音乐卡片的进 AOD 动画按歌名行 / 歌手行**当前文字宽度**算 AOD 下的居中位置，而息屏状态的转发在
     * enterAod 之后才到；两行还是歌词时直接进 AOD，歌名 / 歌手会按歌词的宽度摆放（位置偏移），
     * 普通态和 AOD 态的两份文本也会对不上。所以先写回原值，等一帧重新测宽后再发。
     */
    @JvmStatic
    fun beforeMamlCommand(view: Any, command: String?, resend: Runnable): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return runCatching {
            when (command) {
                "enterAod" -> {
                    if (pendingEnterAod.isNotEmpty()) {
                        pendingEnterAod[view] = resend
                        return@runCatching true
                    }
                    val wasShowing = synchronized(lock) { cards.values.any { it.stock && it.showing != null } }
                    onAodChanged(true)
                    // AOD 下也显示歌词：歌手行进 AOD 前后都是歌词，宽度一致，直接放行。
                    if (!wasShowing || isShowInAod()) return@runCatching false
                    pendingEnterAod[view] = resend
                    main.postDelayed(flushEnterAod, ENTER_AOD_DEFER_MS)
                    true
                }
                "exitAod" -> {
                    // enterAod 还没发出去就亮屏了：卡片从没进过 AOD，两条命令都不发。
                    pendingEnterAod.remove(view) ?: return@runCatching false
                    if (pendingEnterAod.isEmpty()) main.removeCallbacks(flushEnterAod)
                    true
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun flushPendingEnterAod() {
        val actions = pendingEnterAod.values.toList()
        pendingEnterAod.clear()
        actions.forEach { action -> runCatching { action.run() }.onFailure { log(Log.DEBUG, "Rear lyric resend enterAod failed: $it") } }
    }

    // ---- 开关 ----

    private fun setEnabled(enabled: Boolean) {
        PrefsBridge.setRemoteBooleanOverride(Constants.KEY_ENABLE_REAR_LYRIC, enabled)
        if (enabled) {
            startSource()
        } else {
            val current = synchronized(lock) { source }
            runCatching { BackgroundTasks.execute { current?.stop() } }
            synchronized(lock) { latest = null }
        }
        runOnMain { applyAll() }
    }

    private fun setShowInAod(enabled: Boolean) {
        PrefsBridge.setRemoteBooleanOverride(Constants.KEY_REAR_LYRIC_IN_AOD, enabled)
        runOnMain { applyAll() }
    }

    private fun isShowInAod(): Boolean {
        val current = synchronized(lock) { module } ?: return false
        return runCatching { PrefsBridge.shouldShowRearLyricInAod(current) }.getOrDefault(false)
    }

    private fun isEnabled(): Boolean {
        val current = synchronized(lock) { module } ?: return false
        return runCatching { PrefsBridge.shouldShowRearLyric(current) }.getOrDefault(false)
    }

    private fun startSource() {
        val current = synchronized(lock) { source } ?: return
        if (current.isRegistered) return
        runCatching {
            BackgroundTasks.execute {
                if (!isEnabled()) return@execute
                if (!current.start()) log(Log.INFO, "SuperLyric unavailable: ${current.backendType}")
            }
        }
    }

    private fun ensureReceiver(activity: Activity) {
        synchronized(lock) {
            if (receiverActivity.get() === activity && receiver != null) return
            val old = receiverActivity.get()
            receiver?.let { r -> old?.let { runCatching { it.unregisterReceiver(r) } } }
            val next = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Constants.ACTION_SYNC_REAR_LYRIC) return
                    val value = intent.getBooleanExtra(Constants.EXTRA_PREFERENCE_VALUE, false)
                    when (intent.getStringExtra(Constants.EXTRA_PREFERENCE_KEY)) {
                        Constants.KEY_REAR_LYRIC_IN_AOD -> setShowInAod(value)
                        // 旧版模块 App 不带键名：就是总开关。
                        null, Constants.KEY_ENABLE_REAR_LYRIC -> setEnabled(value)
                    }
                }
            }
            val registered = runCatching {
                // 只收持有模块签名权限的发送方（模块 App 自己）。
                activity.registerReceiver(
                    next,
                    IntentFilter(Constants.ACTION_SYNC_REAR_LYRIC),
                    Constants.PERMISSION_SYNC_HOST,
                    null,
                    Context.RECEIVER_EXPORTED,
                )
            }.isSuccess
            if (registered) {
                receiver = next
                receiverActivity = WeakReference(activity)
            }
        }
    }

    // ---- 歌词事件（主线程） ----

    private fun onLine(update: LyricLineUpdate) {
        synchronized(lock) { latest = update }
        applyAll()
    }

    private fun onStop(stop: LyricSession) {
        synchronized(lock) {
            val current = latest ?: return
            if (!current.session.acceptsStop(stop)) return
            latest = null
        }
        applyAll()
    }

    // ---- 写卡片（主线程） ----

    private fun applyAll() {
        val elements = synchronized(lock) { cards.keys.toList() }
        elements.forEach(::applyCard)
    }

    private fun applyCard(element: Any) {
        val refl = reflection() ?: return
        val enabled = isEnabled()
        val (card, pushed, resumed, aod) = synchronized(lock) {
            val card = cards[element] ?: return
            Quad(card, latest, launcherResumed, inAod || SystemClock.uptimeMillis() < aodHoldUntil)
        }
        try {
            val title = refl.title(element)
            val state = LyricCardState(
                enabled = enabled,
                cardPackage = refl.clientPackage(element),
                cardPlaying = refl.playing(element),
                cardVisible = refl.visible(element),
                launcherResumed = resumed,
                inAod = aod,
                cardTitle = title,
                showInAod = aod && isShowInAod(),
            )
            // 卡片 MediaController 的进度和 v4 帧里的进度快照，取更新的那份外推到现在。
            val progress = if (pushed?.timeline != null) {
                ProgressAnchor.freshest(refl.progress(element), pushed.anchor)?.let {
                    Progress(it.positionAt(SystemClock.elapsedRealtime()), it.speed)
                }
            } else {
                null
            }
            val line = pushed?.let { RearLyricPolicy.resolveLine(it, progress?.position, title) }
            val text = RearLyricPolicy.lyricToShow(line, state)
            // 发布方只推了全量歌词、不逐句推送时，按时间轴在下一句开始时再刷新。
            val sameApp = pushed?.session?.publisher.let { it.isNullOrBlank() || it == state.cardPackage }
            val onScreen = if (aod) state.showInAod else state.cardVisible && resumed
            if (pushed != null && sameApp && state.enabled && state.cardPlaying && onScreen) {
                RearLyricPolicy.nextTimelineDelay(pushed, progress?.position, progress?.speed ?: 1f, title, TIMELINE_MAX_DELAY_MS)
                    ?.let(::scheduleTick)
            }
            if (hook.HyperBackscreen.BuildConfig.DEBUG) {
                val trace = "line='${line?.text}' pub=${line?.session?.publisher} paused=${line?.paused} " +
                    "timeline=${pushed?.timeline?.lines?.size} pos=${progress?.position} v4pos=${pushed?.anchor?.position} " +
                    "state=$state stock=${card.stock} -> show='${text}'"
                if (trace != card.lastTrace) {
                    card.lastTrace = trace
                    log(Log.INFO, "lyric trace: $trace")
                }
            }
            var dirty = false
            synchronized(lock) {
                if (card.stock) {
                    // 显示歌词时：歌名行 = 当前这句，歌手行 = 「歌名 - 歌手」（和车载歌词同一种排版）。
                    if (text != null) {
                        if (card.showing == null && card.originalTitle == null && card.originalArtist == null) {
                            card.originalTitle = refl.titleText(element)
                            card.originalArtist = refl.artist(element)
                        }
                        if (text != card.showing) {
                            refl.setTitleText(element, text)
                            refl.setArtist(element, RearLyricPolicy.songLine(card.originalTitle, card.originalArtist, line))
                            card.showing = text
                            dirty = true
                        }
                    } else if (card.showing != null) {
                        refl.setTitleText(element, card.originalTitle.orEmpty())
                        refl.setArtist(element, card.originalArtist.orEmpty())
                        card.showing = null
                        dirty = true
                    }
                }
                // 宿主自己解析出原生歌词（metadata 里的 LRC）时让它管 lyric_current，不抢。
                if (text != null && !refl.hasNativeLyric(element)) {
                    if (refl.setLyricCurrent(element, text)) {
                        card.wroteLyricVar = true
                        dirty = true
                    }
                } else if (card.wroteLyricVar) {
                    refl.setLyricCurrent(element, "")
                    card.wroteLyricVar = false
                    dirty = true
                }
            }
            if (dirty) refl.requestUpdate(element)
        } catch (error: Throwable) {
            log(Log.DEBUG, "Rear lyric apply failed: $error")
        }
    }

    /** 在 [delay] 毫秒后按时间轴刷新一次；已经排了更早的就不动。主线程调用。 */
    private fun scheduleTick(delay: Long) {
        val at = SystemClock.uptimeMillis() + delay
        if (tickAt != 0L && tickAt <= at) return
        main.removeCallbacks(timelineTick)
        tickAt = at
        main.postAtTime(timelineTick, at)
    }

    private fun isTracked(element: Any): Boolean = synchronized(lock) { cards.containsKey(element) }

    private fun reflection(): Reflection? = synchronized(lock) {
        if (reflection != null || reflectionFailed) return reflection
        val cards = cards.keys.firstOrNull() ?: return null
        reflection = runCatching { Reflection.of(cards.javaClass) }.getOrNull()
        if (reflection == null) {
            reflectionFailed = true
            log(Log.WARN, "Rear lyric disabled: MusicControlScreenElement members missing")
        }
        reflection
    }

    private fun runOnMain(block: () -> Unit) {
        val safe = { runCatching(block).onFailure { log(Log.DEBUG, "Rear lyric task failed: $it") } }
        if (Looper.myLooper() == Looper.getMainLooper()) safe() else main.post { safe() }
    }

    private fun log(level: Int, message: String) {
        val current = synchronized(lock) { module }
        if (current != null) runCatching { current.log(level, TAG, message) } else Log.println(level, TAG, message)
    }

    private data class Quad(val card: Card, val line: LyricLineUpdate?, val resumed: Boolean, val aod: Boolean)

    /** 当前播放进度（毫秒）和播放速度。 */
    private class Progress(val position: Long, val speed: Float)

    /** MusicControlScreenElement 上要用到的成员，全部是 MAML 稳定名；缺任何一个整个功能不工作。 */
    private class Reflection(
        private val titleVar: Field,
        private val artistVar: Field,
        private val lyricCurrentVar: Field,
        private val musicController: Field,
        private val playingField: Field,
        private val nativeLyric: Field?,
        private val resumedField: Field?,
        private val isVisible: Method,
        private val requestUpdate: Method,
        private val rendererController: Method?,
        private val controllerRequestUpdate: Method?,
        private val setValue: Method,
        private val getString: Method,
        private val metadataField: Field?,
    ) {
        fun artist(element: Any): String? = artistVar.get(element)?.let { getString.invoke(it) as? String }

        /** 卡片歌名行变量（music_control.title）当前的值；和 [title]（元数据里的歌名）不同。 */
        fun titleText(element: Any): String? = titleVar.get(element)?.let { getString.invoke(it) as? String }

        fun setTitleText(element: Any, value: String) {
            titleVar.get(element)?.let { setValue.invoke(it, value) }
        }

        fun setArtist(element: Any, value: String) {
            artistVar.get(element)?.let { setValue.invoke(it, value) }
        }

        /** 写 lyric_current；值没变或变量不存在返回 false。 */
        fun setLyricCurrent(element: Any, value: String): Boolean {
            val variable = lyricCurrentVar.get(element) ?: return false
            if (getString.invoke(variable) as? String == value) return false
            setValue.invoke(variable, value)
            return true
        }

        fun hasNativeLyric(element: Any): Boolean = nativeLyric?.get(element) != null

        fun playing(element: Any): Boolean = playingField.getBoolean(element)

        fun visible(element: Any): Boolean {
            if (isVisible.invoke(element) != true) return false
            // 根暂停（卡片不在前台）时不写；字段缺失时只看可见性。
            return resumedField?.getBoolean(element) ?: true
        }

        fun title(element: Any): String? = runCatching {
            (metadataField?.get(element) as? android.media.MediaMetadata)
                ?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        }.getOrNull()

        /**
         * 卡片绑定的 MediaController 的进度快照（PlaybackState 里的位置和最后更新时刻），由调用方外推。
         * 拿不到或位置未知返回 null。
         */
        fun progress(element: Any): ProgressAnchor? = runCatching {
            val controller = musicController.get(element) ?: return@runCatching null
            val media = controller.javaClass.getMethod("getMediaController").invoke(controller)
                as? android.media.session.MediaController ?: return@runCatching null
            val playback = media.playbackState ?: return@runCatching null
            if (playback.position < 0) return@runCatching null
            ProgressAnchor(
                position = playback.position,
                speed = playback.playbackSpeed,
                elapsedAt = playback.lastPositionUpdateTime,
                playing = playback.state == android.media.session.PlaybackState.STATE_PLAYING,
            )
        }.getOrNull()

        /** 元数据里的媒体时长；拿不到返回 -1。 */
        fun duration(element: Any): Long = runCatching {
            (metadataField?.get(element) as? android.media.MediaMetadata)
                ?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        }.getOrNull() ?: -1L

        fun clientPackage(element: Any): String? {
            val controller = musicController.get(element) ?: return null
            return runCatching {
                controller.javaClass.getMethod("getClientPackageName").invoke(controller) as? String
            }.getOrNull()
        }

        /**
         * 要求重绘一帧。`ScreenElement.requestUpdate()` 只唤醒渲染循环、不置「需要重绘」，AOD 下帧率几乎为 0，
         * 唤醒后发现不需要画又睡了，要等下一次定时刷新（歌词晚到、刚画出来就被下一句盖掉）。
         * `RendererController.requestUpdate()` 会置 mShouldUpdate，下一帧一定重绘；拿不到时退回元素的。
         */
        fun requestUpdate(element: Any) {
            val controller = rendererController?.let { runCatching { it.invoke(element) }.getOrNull() }
            if (controller != null && controllerRequestUpdate != null) {
                controllerRequestUpdate.invoke(controller)
            } else {
                requestUpdate.invoke(element)
            }
        }

        companion object {
            fun of(elementClass: Class<*>): Reflection? {
                var owner: Class<*>? = elementClass
                while (owner != null && owner.name != MUSIC_CONTROL_CLASS) owner = owner.superclass
                val music = owner ?: return null
                fun field(cls: Class<*>, name: String): Field? {
                    var c: Class<*>? = cls
                    while (c != null) {
                        runCatching { c.getDeclaredField(name) }.getOrNull()?.let {
                            it.isAccessible = true
                            return it
                        }
                        c = c.superclass
                    }
                    return null
                }
                val artistVar = field(music, "mArtistVar") ?: return null
                val titleVar = field(music, "mTitleVar") ?: return null
                val lyricVar = field(music, "mLyricCurrentVar") ?: return null
                val controller = field(music, "mMusicController") ?: return null
                val playing = field(music, "mPlaying")?.takeIf { it.type == Boolean::class.javaPrimitiveType }
                    ?: return null
                val variableClass = artistVar.type
                val setValue = runCatching { variableClass.getMethod("set", Any::class.java) }.getOrNull()
                    ?: return null
                val getString = runCatching { variableClass.getMethod("getString") }.getOrNull() ?: return null
                val isVisible = runCatching { music.getMethod("isVisible") }.getOrNull() ?: return null
                val requestUpdate = runCatching { music.getMethod("requestUpdate") }.getOrNull() ?: return null
                val rendererController = runCatching { music.getMethod("getRendererController") }.getOrNull()
                val controllerRequestUpdate = rendererController?.let { getter ->
                    runCatching { getter.returnType.getMethod("requestUpdate") }.getOrNull()
                }
                return Reflection(
                    titleVar = titleVar,
                    artistVar = artistVar,
                    lyricCurrentVar = lyricVar,
                    musicController = controller,
                    playingField = playing,
                    nativeLyric = field(music, "mLyric"),
                    resumedField = field(music, "mResumed")?.takeIf { it.type == Boolean::class.javaPrimitiveType },
                    isVisible = isVisible,
                    requestUpdate = requestUpdate,
                    rendererController = rendererController,
                    controllerRequestUpdate = controllerRequestUpdate,
                    setValue = setValue,
                    getString = getString,
                    metadataField = field(music, "mMetadata"),
                )
            }
        }
    }
}
