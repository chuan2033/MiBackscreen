package hook.HyperBackscreen.common;

/**
 * 背屏是否跟随主屏熄灭的纯逻辑。
 *
 * <p>系统电源键熄屏时，PowerManagerService.goToSleepInternal 先让主屏（group 0）进入 doze，
 * 再问 PowerManagerServiceImpl.shouldSubScreenGoToSleepGroup(PowerGroup, reason)，
 * 返回 true 就让背屏（group 1）一起 doze；开了 AOD 时背屏随之切到 AOD。
 * 背屏息屏时间选「一直亮屏」时，这里让背屏保持亮屏。
 */
public final class RearScreenSleepPolicy {
    /** PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON。 */
    public static final int GO_TO_SLEEP_REASON_POWER_BUTTON = 4;

    private RearScreenSleepPolicy() {
    }

    /**
     * 按电源键熄灭主屏时背屏是否保持亮屏。
     *
     * @param sleepReason       goToSleep 的原因码
     * @param rearTimeoutMillis 模块偏好里的背屏息屏时间，0 表示跟随系统
     * @param scheduleActive    当前是否处在「定时关闭背屏」时间窗内；窗口内背屏本来就要熄灭
     */
    public static boolean keepsRearAwakeOnPowerButton(int sleepReason, long rearTimeoutMillis,
                                                      boolean scheduleActive) {
        return sleepReason == GO_TO_SLEEP_REASON_POWER_BUTTON
                && rearTimeoutMillis == Constants.REAR_SCREEN_OFF_TIMEOUT_ALWAYS_MS
                && !scheduleActive;
    }
}
