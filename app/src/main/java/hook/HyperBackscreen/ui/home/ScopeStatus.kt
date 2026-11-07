package hook.HyperBackscreen.ui.home

import android.util.Log
import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService

/** 一个作用域在主页状态卡上显示的条目，顺序与重启弹窗、功能页分组保持一致。 */
internal data class ScopeStatusItem(
    val packageName: String,
    @get:StringRes val shortLabelRes: Int,
    @get:StringRes val labelRes: Int,
    val state: ScopeState,
) {
    /** system_server 的作用域没法单独重启，旧版本时要提示的是重启手机。 */
    @get:StringRes
    val descriptionRes: Int
        get() = if (packageName == Constants.SYSTEM_PACKAGE && state == ScopeState.STALE) {
            R.string.scope_state_stale_system
        } else {
            state.descriptionRes
        }
}

/**
 * 作用域当前的注入状态。
 *
 * 状态来自 Xposed 服务的 `getRunningTargets()`（框架记录的、真正加载了本模块的进程）
 * 与 `getScope()`（用户在 LSPosed 里勾选的作用域），不是本模块自己的推测。
 */
internal enum class ScopeState(@get:StringRes val descriptionRes: Int) {
    /** 进程已加载当前版本的模块。 */
    ACTIVE(R.string.scope_state_active),

    /** 进程里还是旧版本模块（更新后没重启作用域），或者正在热重载。 */
    STALE(R.string.scope_state_stale),

    /** 框架报告模块在该进程加载失败。 */
    FAILED(R.string.scope_state_failed),

    /** 已勾选作用域，但对应进程当前没有运行（或还没被注入）。 */
    IDLE(R.string.scope_state_idle),

    /** LSPosed 里没有勾选这个作用域。 */
    NOT_IN_SCOPE(R.string.scope_state_not_in_scope),
}

private const val TAG = "MiBackscreen"

private data class ScopeDefinition(
    val packageName: String,
    @get:StringRes val shortLabelRes: Int,
    @get:StringRes val labelRes: Int,
)

private val scopeDefinitions = listOf(
    ScopeDefinition(Constants.SYSTEM_PACKAGE, R.string.scope_short_system, R.string.restart_system),
    ScopeDefinition(Constants.TARGET_PACKAGE, R.string.scope_short_backscreen, R.string.restart_backscreen),
    ScopeDefinition(Constants.THEME_STORE_PACKAGE, R.string.scope_short_theme, R.string.restart_theme_manager),
    ScopeDefinition(
        Constants.PERSONAL_ASSISTANT_PACKAGE,
        R.string.scope_short_personal_assistant,
        R.string.restart_personal_assistant
    ),
)

/** system_server 在框架里可能以这几种进程名出现。 */
private val systemProcessNames = setOf("system", "system_server", "android")

/**
 * 读取四个作用域的状态。需要 API 102 的服务；服务不可用或调用失败时返回 null，
 * 调用方据此直接不显示圆点，而不是显示一排误导性的「未运行」。
 * 会走 Binder，必须在后台线程调用。
 */
internal fun readScopeStatus(service: XposedService?): List<ScopeStatusItem>? {
    if (service == null) return null
    // getScope / getRunningTargets 是 API 102 才有的接口；模块 minApiVersion 是 101，
    // 跑在 101 的框架上时直接不显示圆点，不去调不存在的方法。
    if (service.apiVersion < 102) return null
    return try {
        val scope = service.scope.toSet()
        val targets = service.runningTargets
        Log.d(TAG, "scope status: scope=$scope targets=" +
            targets.joinToString { "${it.processName}(${it.state}, v${it.loadedVersionCode})" })
        scopeDefinitions.map { definition ->
            val matched = targets.filter { matchesScope(it.processName, definition.packageName) }
            val state = when {
                matched.any { it.state == HookedTarget.State.UP_TO_DATE } -> ScopeState.ACTIVE
                matched.any {
                    it.state == HookedTarget.State.STALE || it.state == HookedTarget.State.RELOADING
                } -> ScopeState.STALE
                matched.any { it.state == HookedTarget.State.FAILED } -> ScopeState.FAILED
                definition.packageName !in scope -> ScopeState.NOT_IN_SCOPE
                else -> ScopeState.IDLE
            }
            ScopeStatusItem(definition.packageName, definition.shortLabelRes, definition.labelRes, state)
        }
    } catch (error: Throwable) {
        Log.w(TAG, "Unable to read scope status", error)
        null
    }
}

private fun matchesScope(process: String, packageName: String): Boolean {
    if (packageName == Constants.SYSTEM_PACKAGE) return process in systemProcessNames
    return process == packageName || process.startsWith("$packageName:")
}
