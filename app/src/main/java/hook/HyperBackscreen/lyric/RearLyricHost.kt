package hook.HyperBackscreen.lyric

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
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
 * 背屏实时歌词（方案 B1）：把 SuperLyric 推来的当前歌词行写进背屏音乐卡片的「歌手」行。
 *
 * - 只改背屏自带的音乐卡片（卡片 XML 里有 `album_artist_text`），其余 MusicControl（用户主题里的）
 *   只写 `music_control.lyric_current`，让本来就引用歌词变量的主题也能显示；
 * - 条件不满足（见 [RearLyricPolicy.lyricToShow]）时恢复原歌手名；
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
        /** 宿主最近一次 updateAlbum 写入的歌手名；恢复时用。 */
        var originalArtist: String? = null
        /** 当前写在歌手行上的歌词；null 表示歌手行是原样。 */
        var showing: String? = null
        /** 是否由我们写过 lyric_current（没有原生歌词时才写，恢复时清空）。 */
        var wroteLyricVar = false
    }

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

    /** updateAlbum 之前：记下宿主要写的歌手名，供恢复用。 */
    @JvmStatic
    fun beforeUpdateAlbum(element: Any, artist: Any?) {
        synchronized(lock) {
            val card = cards[element] ?: return
            card.originalArtist = artist as? String
            // 宿主马上会用原歌手名覆盖歌手行，等 after 里按需再写回歌词。
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
        }
        runOnMain { applyAll() }
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
                    setEnabled(intent.getBooleanExtra(Constants.EXTRA_PREFERENCE_VALUE, false))
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
        val (card, line, resumed, aod) = synchronized(lock) {
            val card = cards[element] ?: return
            Quad(card, latest, launcherResumed, inAod)
        }
        try {
            val state = LyricCardState(
                enabled = enabled,
                cardPackage = refl.clientPackage(element),
                cardPlaying = refl.playing(element),
                cardVisible = refl.visible(element),
                launcherResumed = resumed,
                inAod = aod,
                cardTitle = refl.title(element),
            )
            val text = RearLyricPolicy.lyricToShow(line, state)
            if (hook.HyperBackscreen.BuildConfig.DEBUG) {
                log(Log.INFO, "lyric trace: line='${line?.text}' pub=${line?.session?.publisher} paused=${line?.paused} " +
                    "state=$state stock=${card.stock} -> show='${text}'")
            }
            var dirty = false
            synchronized(lock) {
                if (card.stock) {
                    if (text != null) {
                        if (card.showing == null && card.originalArtist == null) {
                            card.originalArtist = refl.artist(element)
                        }
                        if (text != card.showing) {
                            refl.setArtist(element, text)
                            card.showing = text
                            dirty = true
                        }
                    } else if (card.showing != null) {
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

    /** MusicControlScreenElement 上要用到的成员，全部是 MAML 稳定名；缺任何一个整个功能不工作。 */
    private class Reflection(
        private val artistVar: Field,
        private val lyricCurrentVar: Field,
        private val musicController: Field,
        private val playingField: Field,
        private val nativeLyric: Field?,
        private val resumedField: Field?,
        private val isVisible: Method,
        private val requestUpdate: Method,
        private val setValue: Method,
        private val getString: Method,
        private val metadataField: Field?,
    ) {
        fun artist(element: Any): String? = artistVar.get(element)?.let { getString.invoke(it) as? String }

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

        fun clientPackage(element: Any): String? {
            val controller = musicController.get(element) ?: return null
            return runCatching {
                controller.javaClass.getMethod("getClientPackageName").invoke(controller) as? String
            }.getOrNull()
        }

        fun requestUpdate(element: Any) {
            requestUpdate.invoke(element)
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
                return Reflection(
                    artistVar = artistVar,
                    lyricCurrentVar = lyricVar,
                    musicController = controller,
                    playingField = playing,
                    nativeLyric = field(music, "mLyric"),
                    resumedField = field(music, "mResumed")?.takeIf { it.type == Boolean::class.javaPrimitiveType },
                    isVisible = isVisible,
                    requestUpdate = requestUpdate,
                    setValue = setValue,
                    getString = getString,
                    metadataField = field(music, "mMetadata"),
                )
            }
        }
    }
}
