package hook.HyperBackscreen.hook.target

import android.content.Context
import java.io.File
import java.util.function.Consumer
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher

/**
 * 用 DexKit 按结构解析宿主混淆类，并把结果按宿主版本缓存下来。
 *
 * 解析顺序：内存 -> 版本缓存 -> DexKit 查询 -> 规格里的兜底类名。
 * 只有 DexKit 查询成功才会写入缓存，兜底命中不写，避免把旧版本的名字固化下来。
 */
internal object TargetResolver {
    private val memory = mutableMapOf<String, String>()
    private val diagnostics = mutableListOf<String>()
    private val cacheHolder = arrayOfNulls<JSONObject>(1)

    private var bridge: DexKitBridge? = null
    private var cacheFile: File? = null
    private var hostApkPath: String? = null
    private var logger: Consumer<String>? = null
    private var dexKitState = "未初始化"

    /** 开机早期写缓存会失败；标脏后由后台线程补写一次。 */
    @Volatile private var cacheDirty = false
    @Volatile private var cacheFlushScheduled = false
    private val cacheFlushLock = Any()

    private const val CACHE_FLUSH_RETRY_MS = 45_000L

    @JvmStatic
    fun install(
        context: Context?,
        apkPath: String?,
        versionCode: Long,
        log: Consumer<String>,
    ) {
        logger = log
        hostApkPath = apkPath
        cacheFile = context?.let { File(it.filesDir, "host-targets-$versionCode.json") }
        cacheHolder[0] = readCache()
        if (apkPath == null) {
            dexKitState = "宿主 APK 路径缺失"
            return
        }
        dexKitState = runCatching {
            System.loadLibrary("dexkit")
            bridge = DexKitBridge.create(apkPath)
            "就绪"
        }.getOrElse { error ->
            log.accept("Target resolver disabled: ${error.javaClass.simpleName}: ${error.message}")
            "加载失败: ${error.javaClass.simpleName}"
        }
    }

    @JvmStatic
    fun resolveClass(classLoader: ClassLoader, target: ClassTarget): Class<*>? {
        memory[target.id]?.let { name ->
            loadClass(name, classLoader)?.let {
                record(target.id, name, "缓存")
                return it
            }
            memory.remove(target.id)
        }

        val cached = cacheHolder[0]?.optString(target.id)?.takeIf { it.isNotEmpty() }
        if (cached != null) {
            loadClass(cached, classLoader)?.let {
                memory[target.id] = cached
                record(target.id, cached, "缓存")
                return it
            }
            logger?.accept("Cached target stale: ${target.id} -> $cached")
            cacheHolder[0]?.remove(target.id)
        }

        val found = queryDexKit(target)
        if (found != null) {
            loadClass(found, classLoader)?.let {
                memory[target.id] = found
                writeCache(target.id, found)
                record(target.id, found, "DexKit(${target.strings.size} 特征)")
                return it
            }
        }

        target.fallbackNames.firstOrNull()?.let { name ->
            loadClass(name, classLoader)?.let {
                record(target.id, name, "兜底名字")
                logger?.accept("Target resolved by fallback name: ${target.id} -> $name")
                return it
            }
        }

        record(target.id, "<未命中>", "失败")
        logger?.accept("Target missing: ${target.id} packages=${target.packages} strings=${target.strings}")
        return null
    }

    @JvmStatic
    fun snapshot(): List<String> = synchronized(diagnostics) { diagnostics.toList() }

    @JvmStatic
    fun state(): String = dexKitState

    private fun queryDexKit(target: ClassTarget): String? {
        val activeBridge = bridge ?: return null
        if (target.strings.isEmpty()) return null
        return runCatching {
            val finder = FindClass.create()
            if (target.packages.isNotEmpty()) {
                finder.searchPackages(*target.packages.toTypedArray())
            }
            finder.matcher(ClassMatcher.create().usingStrings(*target.strings.toTypedArray()))
            val hits = activeBridge.findClass(finder)
            if (hits.size == 1) hits[0].name else {
                if (hits.size > 1) {
                    logger?.accept("Target ambiguous: ${target.id} -> ${hits.map { it.name }}")
                }
                null
            }
        }.getOrElse { error ->
            logger?.accept("Target query failed: ${target.id}: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun loadClass(name: String, classLoader: ClassLoader): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()

    private fun record(id: String, value: String, via: String) {
        synchronized(diagnostics) {
            diagnostics.removeAll { it.startsWith("$id ") }
            diagnostics += "$id = $value ($via)"
        }
    }

    private fun readCache(): JSONObject? = runCatching {
        cacheFile?.takeIf { it.isFile }?.readText()?.let(::JSONObject)
    }.getOrNull()

    private fun writeCache(id: String, className: String) {
        val file = cacheFile ?: return
        val json = cacheHolder[0] ?: JSONObject().also { cacheHolder[0] = it }
        runCatching {
            json.put(id, className)
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
            cacheDirty = false
        }.onFailure {
            // 开机早期（首次解锁前）宿主的 CE 存储不可写，会走到这里；
            // 标脏后延迟重试，解锁后就能补上，不必等到下次进程重启。
            cacheDirty = true
            logger?.accept(
                "Target cache write failed: ${it.javaClass.simpleName}: ${it.message}"
                        + " path=${file.absolutePath}"
            )
            scheduleCacheFlush()
        }
    }

    /** 后台补写缓存；同一时刻只挂一个重试线程。 */
    private fun scheduleCacheFlush() {
        synchronized(cacheFlushLock) {
            if (cacheFlushScheduled) return
            cacheFlushScheduled = true
        }
        Thread({
            try {
                Thread.sleep(CACHE_FLUSH_RETRY_MS)
                flushCache()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(cacheFlushLock) { cacheFlushScheduled = false }
            }
        }, "MiBackscreenTargetCache").apply {
            isDaemon = true
            start()
        }
    }

    private fun flushCache() {
        val file = cacheFile ?: return
        val json = cacheHolder[0] ?: return
        if (!cacheDirty) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
            cacheDirty = false
            logger?.accept("Target cache flushed: ${file.absolutePath}")
        }.onFailure {
            logger?.accept("Target cache flush failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
