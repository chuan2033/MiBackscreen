package hook.HyperBackscreen.hook.target

import hook.HyperBackscreen.BuildConfig
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.lang.reflect.Method
import java.util.function.Consumer
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import org.luckypray.dexkit.query.matchers.UsingFieldMatcher

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

    /** 本进程内已确认查不到的目标（类 id 或 `method:<id>`），不再重复扫描 dex。 */
    private val missingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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

    /**
     * 单次 DexKit 查询超过这个时长就记一条日志。
     *
     * 查询发生在宿主进程启动的主线程上（`onPackageLoaded` → `installSubscreen*Hooks`），
     * 缓存未命中时是全 dex 扫描。几百毫秒属正常，超过这个阈值说明宿主 APK 变大或
     * 磁盘变慢，需要重新评估是否把解析挪到后台。
     */
    private const val SLOW_QUERY_MS = 300L

    /** 缓存格式版本。判据或结果语义调整时改一次，旧的负缓存与旧名字缓存自然作废。 */
    private const val CACHE_SCHEMA = "v2"

    /**
     * 负结果标记。
     *
     * 宿主同一个版本内，一个目标确认查不到之后不再重复查询：写进缓存文件，下次进程启动直接读它；
     * 同进程内的重复调用由 [missingIds] 拦住。做法与 QAuxiliary 的 `NO_SUCH_METHOD` 一致。
     */
    private const val MISSING = "<missing>"

    /**
     * 一次定位的结果。
     *
     * 遍历结束没有命中（[Failure.NO_MATCH]）与「命中多个、无法判定」（[Failure.AMBIGUOUS]）
     * 都算查找失败：调用方拿到失败后直接终止，不再用旧名字继续猜。
     * [Failure.NOT_READY] 与 [Failure.NO_FEATURE] 表示这次根本没法执行查找（DexKit 没加载、
     * 规格里没有特征），属于能力缺失，允许降级到兜底名字。
     */
    private class Lookup(
        val name: String?,
        val failure: Failure?,
        val detail: String = "",
    ) {
        enum class Failure { NOT_READY, NO_FEATURE, NO_MATCH, AMBIGUOUS, QUERY_ERROR }

        val isHit: Boolean get() = name != null

        val allowsFallback: Boolean
            get() = failure == Failure.NOT_READY || failure == Failure.NO_FEATURE

        /** 日志与诊断快照里的失败原因；命中时为空串。 */
        val reason: String
            get() = when (failure) {
                null -> ""
                Failure.NOT_READY -> "dexkit-not-ready"
                Failure.NO_FEATURE -> "no-feature"
                Failure.NO_MATCH -> "no-match"
                Failure.AMBIGUOUS -> "ambiguous"
                Failure.QUERY_ERROR -> "query-error"
            }
    }

    @JvmStatic
    fun install(
        context: Context?,
        apkPath: String?,
        versionCode: Long,
        log: Consumer<String>,
    ) {
        logger = log
        hostApkPath = apkPath
        // 缓存键同时带上模块版本：模块升级修正了某个目标的判据时，旧的命中 / <missing> 不能继续沿用。
        cacheFile = context?.let {
            File(it.filesDir, "host-targets-$versionCode-$CACHE_SCHEMA-m${BuildConfig.VERSION_CODE}.json")
        }
        cacheHolder[0] = readCache()
        // 宿主或模块升级后旧缓存不再命中，顺手清掉，不在宿主数据目录里越积越多。
        cacheFile?.let { current ->
            runCatching {
                current.parentFile?.listFiles { file ->
                    file.name.startsWith("host-targets-") && file.name.endsWith(".json") && file != current
                }?.forEach { it.delete() }
            }
        }
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
        if (missingIds.contains(target.id)) {
            return failClass(target, "cached-missing", null)
        }

        memory[target.id]?.let { name ->
            loadClass(name, classLoader)?.let {
                record(target.id, name, "内存")
                return it
            }
            memory.remove(target.id)
        }

        val cached = cacheHolder[0]?.optString(target.id)?.takeIf { it.isNotEmpty() }
        if (cached == MISSING) {
            missingIds.add(target.id)
            return failClass(target, "cached-missing", null)
        }
        if (cached != null) {
            loadClass(cached, classLoader)?.let {
                memory[target.id] = cached
                record(target.id, cached, "缓存")
                return it
            }
            logger?.accept("Cached target stale: ${target.id} -> $cached")
            cacheHolder[0]?.remove(target.id)
        }

        val lookup = queryDexKit(target)
        val hit = lookup.name
        if (hit != null) {
            val loaded = loadClass(hit, classLoader)
            if (loaded != null) {
                memory[target.id] = hit
                writeCache(target.id, hit)
                record(target.id, hit, "DexKit(${target.strings.size} 特征)")
                return loaded
            }
            return failClass(target, "load-failed", hit)
        }

        if (lookup.allowsFallback) {
            target.fallbackNames.firstOrNull()?.let { name ->
                loadClass(name, classLoader)?.let {
                    record(target.id, name, "兜底名字")
                    logger?.accept(
                        "Target resolved by fallback name: ${target.id} -> $name (${lookup.reason})"
                    )
                    return it
                }
            }
            return failClass(target, "${lookup.reason}, fallback-missing", null)
        }

        rememberMissing(target.id, lookup.failure)
        return failClass(target, lookup.reason, lookup.detail)
    }

    /**
     * 记下「这个目标在宿主当前版本里查不到」。
     *
     * 只缓存 [Lookup.Failure.NO_MATCH] 与 [Lookup.Failure.AMBIGUOUS]：前者说明遍历结束没有候选，
     * 后者说明判据命中了多个、需要在规格里补特征，这两种结论在宿主版本不变时不会变。
     * `QUERY_ERROR` 可能是临时故障（dex 尚未就绪、内存紧张），下次进程启动仍应重试，不写入。
     */
    private fun rememberMissing(key: String, failure: Lookup.Failure?) {
        if (failure != Lookup.Failure.NO_MATCH && failure != Lookup.Failure.AMBIGUOUS) return
        missingIds.add(key)
        writeCache(key, MISSING)
    }

    /** 记录一次「未找到」并返回 null，调用方据此跳过安装，不再尝试其它猜法。 */
    private fun failClass(target: ClassTarget, reason: String, resolved: String?): Class<*>? {
        val detail = buildString {
            append("id=").append(target.id)
            append(" kind=class")
            append(" reason=").append(reason)
            if (!resolved.isNullOrEmpty()) append(" resolved=").append(resolved)
            if (target.strings.isNotEmpty()) append(" features=").append(target.strings)
            if (target.packages.isNotEmpty()) append(" packages=").append(target.packages)
        }
        record(target.id, "<未找到: $reason>", "失败")
        logger?.accept("Target not found: $detail")
        return null
    }

    @JvmStatic
    fun snapshot(): List<String> = synchronized(diagnostics) { diagnostics.toList() }

    /**
     * 按结构解析宿主混淆方法，缓存与 [resolveClass] 共用同一个文件。
     *
     * 解析顺序：内存 → 版本缓存 → DexKit 查询 →（仅 DexKit 无法执行查找时）兜底方法名。
     * 遍历结束没有命中视为失败，调用方跳过安装，不再用兜底名字继续猜，判定见 [Lookup]。
     */
    @JvmStatic
    fun resolveMethod(classLoader: ClassLoader, target: MethodTarget): Method? {
        val cacheKey = "method:${target.id}"
        if (missingIds.contains(cacheKey)) {
            return failMethod(target, "cached-missing", null)
        }

        memory[cacheKey]?.let { name ->
            findMethodByName(name, target, classLoader)?.let {
                record(cacheKey, name, "内存")
                return it
            }
            memory.remove(cacheKey)
        }

        val cached = cacheHolder[0]?.optString(cacheKey)?.takeIf { it.isNotEmpty() }
        if (cached == MISSING) {
            missingIds.add(cacheKey)
            return failMethod(target, "cached-missing", null)
        }
        if (cached != null) {
            findMethodByName(cached, target, classLoader)?.let {
                memory[cacheKey] = cached
                record(cacheKey, cached, "缓存")
                return it
            }
            logger?.accept("Cached method target stale: ${target.id} -> $cached")
            cacheHolder[0]?.remove(cacheKey)
        }

        val lookup = queryDexKitMethod(target)
        val hit = lookup.name
        if (hit != null) {
            val resolved = findMethodByName(hit, target, classLoader)
            if (resolved != null) {
                memory[cacheKey] = hit
                writeCache(cacheKey, hit)
                record(cacheKey, hit, "DexKit")
                return resolved
            }
            return failMethod(target, "load-failed", hit)
        }

        if (lookup.allowsFallback) {
            findMethodByName(target.fallbackName, target, classLoader)?.let {
                record(cacheKey, target.fallbackName, "兜底名字")
                logger?.accept(
                    "Method target resolved by fallback name: ${target.id} -> ${target.fallbackName}"
                            + " (${lookup.reason})"
                )
                return it
            }
            return failMethod(target, "${lookup.reason}, fallback-missing", target.fallbackName)
        }

        rememberMissing(cacheKey, lookup.failure)
        return failMethod(target, lookup.reason, lookup.detail)
    }

    /** 记录一次「未找到」并返回 null，调用方据此跳过安装，不再尝试其它猜法。 */
    private fun failMethod(target: MethodTarget, reason: String, resolved: String?): Method? {
        val detail = buildString {
            append("id=").append(target.id)
            append(" kind=method")
            append(" owner=").append(target.ownerClass)
            append(" reason=").append(reason)
            if (!resolved.isNullOrEmpty()) append(" resolved=").append(resolved)
        }
        record("method:${target.id}", "<未找到: $reason>", "失败")
        logger?.accept("Target not found: $detail")
        return null
    }

    private fun findMethodByName(
        name: String,
        target: MethodTarget,
        classLoader: ClassLoader,
    ): Method? = runCatching {
        val owner = Class.forName(target.ownerClass, false, classLoader)
        val paramTypes = target.parameterTypes.map {
            when (it) {
                "int" -> Int::class.javaPrimitiveType!!
                "long" -> Long::class.javaPrimitiveType!!
                "boolean" -> Boolean::class.javaPrimitiveType!!
                "float" -> Float::class.javaPrimitiveType!!
                "double" -> Double::class.javaPrimitiveType!!
                "short" -> Short::class.javaPrimitiveType!!
                "byte" -> Byte::class.javaPrimitiveType!!
                "char" -> Char::class.javaPrimitiveType!!
                "void" -> Void::class.javaPrimitiveType!!
                else -> Class.forName(it, false, classLoader)
            }
        }.toTypedArray()
        val method = owner.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        method
    }.getOrNull()

    private fun queryDexKitMethod(target: MethodTarget): Lookup {
        val activeBridge = bridge ?: return Lookup(null, Lookup.Failure.NOT_READY)
        if (target.bodyStrings.isEmpty() && target.usingFieldDescriptors.isEmpty()
            && target.usingFieldTypes.isEmpty() && target.usingNumbers.isEmpty()
            && target.invokeMethodSpecs.isEmpty()
        ) return Lookup(null, Lookup.Failure.NO_FEATURE)
        val startedAt = SystemClock.elapsedRealtime()
        val result = runCatching {
            val matcher = MethodMatcher.create()
                .declaredClass(target.ownerClass)
            if (target.returnType != null) {
                matcher.returnType(target.returnType)
            }
            // 参数列表长度隐含参数个数：空列表 = 无参方法，必须写入才能约束参数个数。
            matcher.paramTypes(*target.parameterTypes.toTypedArray())
            if (target.bodyStrings.isNotEmpty()) {
                matcher.usingEqStrings(*target.bodyStrings.toTypedArray())
            }
            if (target.usingFieldDescriptors.isNotEmpty()
                || target.usingFieldTypes.isNotEmpty()
            ) {
                val fieldMatchers = buildList {
                    target.usingFieldDescriptors.forEach { desc ->
                        add(UsingFieldMatcher(desc, UsingType.Read))
                    }
                    target.usingFieldTypes.forEach { anchor ->
                        add(
                            UsingFieldMatcher()
                                .declaredClass(anchor.declaredClass)
                                .type(anchor.typeName)
                                .usingType(UsingType.Read)
                        )
                    }
                }
                matcher.usingFields(fieldMatchers)
            }
            if (target.usingNumbers.isNotEmpty()) {
                matcher.usingNumbers(*target.usingNumbers.toTypedArray())
            }
            if (target.invokeMethodSpecs.isNotEmpty()) {
                val methodsMatcher = MethodsMatcher()
                target.invokeMethodSpecs.forEach { spec ->
                    val invokeMatcher = MethodMatcher()
                    spec.declaringClass?.let { invokeMatcher.declaredClass(it) }
                    spec.returnType?.let { invokeMatcher.returnType(it) }
                    spec.parameterTypes?.let { types ->
                        invokeMatcher.paramTypes(*types.toTypedArray())
                    }
                    methodsMatcher.add(invokeMatcher)
                }
                matcher.invokeMethods(methodsMatcher)
            }
            val finder = FindMethod.create().matcher(matcher)
            activeBridge.findMethod(finder)
        }
        reportQueryCost("method:${target.id}", startedAt)
        return result.fold(
            onSuccess = { hits ->
                when {
                    hits.size == 1 -> Lookup(hits[0].name, null)
                    hits.size > 1 && target.pickFirstWhenAmbiguous -> {
                        val names = hits.map { it.name }
                        val preferred = hits.firstOrNull { it.name == target.fallbackName }
                            ?: hits.sortedBy { it.name }.first()
                        logger?.accept(
                            "Method target alias pick: ${target.id} -> ${preferred.name} of $names"
                        )
                        Lookup(preferred.name, null)
                    }
                    hits.isEmpty() -> Lookup(null, Lookup.Failure.NO_MATCH)
                    else -> {
                        val names = hits.map { it.name }
                        logger?.accept("Method target ambiguous: ${target.id} -> $names")
                        Lookup(null, Lookup.Failure.AMBIGUOUS, names.joinToString(","))
                    }
                }
            },
            onFailure = { error ->
                logger?.accept(
                    "Method target query failed: ${target.id}: "
                            + "${error.javaClass.simpleName}: ${error.message}"
                )
                Lookup(null, Lookup.Failure.QUERY_ERROR, error.javaClass.simpleName)
            },
        )
    }

    @JvmStatic
    fun state(): String = dexKitState

    private fun queryDexKit(target: ClassTarget): Lookup {
        val activeBridge = bridge ?: return Lookup(null, Lookup.Failure.NOT_READY)
        if (target.strings.isEmpty()) return Lookup(null, Lookup.Failure.NO_FEATURE)
        val startedAt = SystemClock.elapsedRealtime()
        val result = runCatching {
            val finder = FindClass.create()
            if (target.packages.isNotEmpty()) {
                finder.searchPackages(*target.packages.toTypedArray())
            }
            finder.matcher(ClassMatcher.create().usingStrings(*target.strings.toTypedArray()))
            activeBridge.findClass(finder)
        }
        reportQueryCost("class:${target.id}", startedAt)
        return result.fold(
            onSuccess = { hits ->
                when {
                    hits.size == 1 -> Lookup(hits[0].name, null)
                    hits.isEmpty() -> Lookup(null, Lookup.Failure.NO_MATCH)
                    else -> {
                        val names = hits.map { it.name }
                        logger?.accept("Target ambiguous: ${target.id} -> $names")
                        Lookup(null, Lookup.Failure.AMBIGUOUS, names.joinToString(","))
                    }
                }
            },
            onFailure = { error ->
                logger?.accept("Target query failed: ${target.id}: ${error.javaClass.simpleName}")
                Lookup(null, Lookup.Failure.QUERY_ERROR, error.javaClass.simpleName)
            },
        )
    }

    /** 查询耗时超过阈值时记一条，供诊断日志导出后判断宿主启动变慢的来源。 */
    private fun reportQueryCost(id: String, startedAt: Long) {
        val cost = SystemClock.elapsedRealtime() - startedAt
        if (cost >= SLOW_QUERY_MS) {
            logger?.accept("Slow target query: $id ${cost}ms")
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
