package hook.HyperBackscreen.desktop

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 背屏桌面在模块 App 进程里的共享状态：Activity、前台服务、磁贴、设置页都在这个进程，直接共用。
 */
internal object RearDesktopState {
    private val _running = MutableStateFlow(false)
    /** 前台服务在运行（会话已开始或正在开始）。 */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _desktopResumed = MutableStateFlow(false)
    /** 背屏桌面在前台：浮动控制条只在其它应用盖住桌面时显示。 */
    val desktopResumed: StateFlow<Boolean> = _desktopResumed.asStateFlow()

    private val _exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 退出请求（通知、控制条、磁贴发出），桌面 Activity 收到后结束自己。 */
    val exitRequests: SharedFlow<Unit> = _exitRequests.asSharedFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setDesktopResumed(value: Boolean) {
        _desktopResumed.value = value
    }

    fun requestExit() {
        _exitRequests.tryEmit(Unit)
    }
}
