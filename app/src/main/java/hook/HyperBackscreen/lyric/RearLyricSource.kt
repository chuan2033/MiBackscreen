package hook.HyperBackscreen.lyric

import android.annotation.SuppressLint
import android.os.IBinder
import com.hchen.superlyricapi.ISuperLyricManager
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.LyricListener
import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.SuperLyric
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.model.PlaybackState
import com.hchen.superlyricapi.model.Stop

/**
 * SuperLyric 订阅（只用在背屏中心进程里）。
 *
 * SuperLyric 在 system_server 里把服务 Binder 塞进每个应用进程的 ServiceManager 缓存，所以这里直接
 * `ServiceManager.getService` 就能拿到，不需要权限也不需要被 SuperLyric 勾选。先查 v4，有就用 4.0 库的
 * receiver；没有再直连 3.x。两个后端只注册一个。
 *
 * 回调在 Binder 线程 / 库内部线程，[onLine] / [onStop] 由调用方切回主线程。
 */
internal class RearLyricSource(
    private val onLine: (LyricLineUpdate) -> Unit,
    private val onStop: (LyricSession) -> Unit,
    private val log: (String) -> Unit,
) {
    private interface Backend {
        /** 注册成功返回 true。 */
        fun register(): Boolean
        fun unregister()
        val binder: IBinder?

        /**
         * 主动拉一次「当前这一句」。SuperLyric 只在换句时推送，新订阅方在下一次换句前拿不到当前句，
         * 背屏中心每次重新订阅后会空等一整句（长句 / 间奏可达数十秒），表现为背屏歌词跟不上主屏。
         */
        fun pullLatest() {}
    }

    private val lock = Any()
    private var backend: Backend? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    @Volatile
    var backendType: SuperLyricBackend = SuperLyricBackend.NONE
        private set

    val isRegistered: Boolean get() = synchronized(lock) { backend != null }

    /** 已注册时什么都不做；服务不可用返回 false（静默，等下次背屏桌面回到前台再试）。 */
    fun start(): Boolean {
        synchronized(lock) { if (backend != null) return true }
        val type = SuperLyricBackend.select(::lookupService)
        val created: Backend = when (type) {
            SuperLyricBackend.V4 -> ModernBackend()
            SuperLyricBackend.LEGACY -> {
                val binder = lookupService(SuperLyricServices.LEGACY) ?: return false
                LegacyBackend(ISuperLyricManager.Stub.asInterface(binder), binder)
            }
            SuperLyricBackend.NONE -> {
                backendType = SuperLyricBackend.NONE
                return false
            }
        }
        val ok = runCatching { created.register() }.getOrElse {
            log("SuperLyric register failed: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
        if (!ok) {
            runCatching { created.unregister() }
            return false
        }
        synchronized(lock) {
            backend = created
            backendType = type
        }
        watchDeath(created)
        log("SuperLyric receiver registered via $type")
        runCatching { created.pullLatest() }
        return true
    }

    /** 已订阅但手上没有歌词时（例如背屏桌面回到前台）补拉一次当前句。 */
    fun pullLatest() {
        val current = synchronized(lock) { backend } ?: return
        runCatching { current.pullLatest() }
    }

    fun stop() {
        val current = synchronized(lock) {
            val value = backend
            backend = null
            value
        } ?: return
        unwatchDeath(current)
        runCatching { current.unregister() }
        log("SuperLyric receiver unregistered")
    }

    /** SuperLyric 更新或 system_server 重启后旧 Binder 会死：丢掉后端，等下次 start() 重新探测。 */
    private fun watchDeath(target: Backend) {
        val binder = target.binder ?: return
        val recipient = IBinder.DeathRecipient {
            synchronized(lock) { if (backend === target) backend = null }
            log("SuperLyric service died")
            onStop(LyricSession(null, null))
        }
        if (runCatching { binder.linkToDeath(recipient, 0) }.isSuccess) {
            synchronized(lock) { deathRecipient = recipient }
        }
    }

    private fun unwatchDeath(target: Backend) {
        val recipient = synchronized(lock) {
            val value = deathRecipient
            deathRecipient = null
            value
        } ?: return
        runCatching { target.binder?.unlinkToDeath(recipient, 0) }
    }

    /** v4：4.0 库负责解 TLV 帧；注册时若正在播放会立刻推一帧，没推就主动拉一次 latest。 */
    private inner class ModernBackend : Backend {
        private val receiver: Receiver = SuperLyric.receiver()
        @Volatile private var delivered = false
        private val listener = object : LyricListener {
            override fun onLyric(view: LyricView) {
                delivered = true
                onLine(view.toUpdate())
            }

            override fun onStop(stop: Stop) {
                delivered = true
                this@RearLyricSource.onStop(LyricSession(stop.publisher, stop.id))
            }
        }

        override val binder: IBinder? get() = lookupService(SuperLyricServices.V4)

        override fun register(): Boolean {
            val result = receiver.register(listener)
            if (result != Receiver.RegisterResult.OK) {
                log("SuperLyric v4 register rejected: $result")
                return false
            }
            if (!delivered) runCatching { receiver.latest() }.getOrNull()?.let { onLine(it.toUpdate()) }
            return true
        }

        override fun unregister() {
            receiver.unregister(listener)
        }

        private fun LyricView.toUpdate() = LyricLineUpdate(
            session = LyricSession(publisher, lyric.id),
            text = line?.text,
            paused = state != null && state != PlaybackState.PLAYING && state != PlaybackState.BUFFERING,
        )
    }

    /**
     * 3.x 冻结 ABI。每行回调一次 `onLyric(publisher, data)`：单行包只带当前行（或只带下标），
     * 全量包带整首歌词。按 (publisher, lyricId) 缓存全量歌词，单行包只给下标时从缓存里取。
     */
    private inner class LegacyBackend(
        private val manager: ISuperLyricManager,
        override val binder: IBinder,
    ) : Backend {
        private val cacheLock = Any()
        private var cachedSession: LyricSession? = null
        private var cachedLines: Array<out SuperLyricLine?>? = null

        private val stub = object : ISuperLyricReceiver.Stub() {
            override fun onLyric(publisher: String?, data: SuperLyricData?) {
                if (data == null) return
                if (data.isStopPayload) {
                    onStop(publisher, data)
                    return
                }
                onLine(project(publisher, data))
            }

            override fun onStop(publisher: String?, data: SuperLyricData?) {
                val session = LyricSession(publisher, data?.lyricId)
                synchronized(cacheLock) {
                    if (cachedSession?.acceptsStop(session) == true) {
                        cachedSession = null
                        cachedLines = null
                    }
                }
                this@RearLyricSource.onStop(session)
            }
        }

        override fun register(): Boolean {
            manager.registerReceiver(stub)
            return runCatching { manager.isReceiverRegistered(stub) }.getOrDefault(true)
        }

        override fun unregister() {
            if (binder.isBinderAlive) manager.unregisterReceiver(stub)
        }

        override fun pullLatest() {
            val data = manager.latestLyric ?: return
            if (data.isStopPayload) return
            val update = project(null, data)
            if (hook.HyperBackscreen.BuildConfig.DEBUG) {
                log("SuperLyric pulled latest: text='${update.text}' title='${update.title}'")
            }
            if (update.text.isNullOrBlank()) return
            onLine(update)
        }

        private fun project(publisher: String?, data: SuperLyricData): LyricLineUpdate {
            val session = LyricSession(publisher, data.lyricId)
            val lines = synchronized(cacheLock) {
                if (session != cachedSession || session.lyricId.isNullOrBlank()) cachedLines = null
                cachedSession = session
                if (data.hasAllLyrics()) cachedLines = data.allLyrics
                cachedLines
            }
            val index = if (data.hasCurrentLyricIndex()) data.currentLyricIndex else -1
            val line = data.lyric ?: data.getLyricAt(index) ?: lines?.getOrNull(index)
            return LyricLineUpdate(session, line?.text, title = runCatching { data.title }.getOrNull())
        }
    }

    private companion object {
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        fun lookupService(name: String): IBinder? = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, name) as? IBinder
        }.getOrNull()
    }
}
