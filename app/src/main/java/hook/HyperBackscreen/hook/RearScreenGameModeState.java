package hook.HyperBackscreen.hook;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主屏高负载（横屏游戏）状态。
 *
 * <p>{@code DualScreenCoverManager.updateHighLoadSceneMode(int, boolean)} 是系统里唯一
 * 的入口：进入时直接关背屏，退出时恢复背屏双击息屏。这里记录当前是否处于该模式，
 * 供背屏息屏超时的 hook 判断要不要抬到「一直亮屏」。
 *
 * <p>只在 system_server 进程内使用（静态字段随进程），不跨进程共享。
 */
final class RearScreenGameModeState {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    private RearScreenGameModeState() {}

    static boolean isActive() {
        return ACTIVE.get();
    }

    /** @return 状态是否发生了变化（用于抑制重复日志）。 */
    static boolean setActive(boolean active) {
        return ACTIVE.getAndSet(active) != active;
    }
}
