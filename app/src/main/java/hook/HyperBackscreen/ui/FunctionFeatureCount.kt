package hook.HyperBackscreen.ui

/** Counts enabled switches reachable from Functions, not app selections or appearance options. */
internal fun countEnabledFunctions(
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    disableRearScreenCover: Boolean,
    disableDoubleTapWake: Boolean,
    enable18ProFeatures: Boolean,
    enableBatteryRing: Boolean = false,
    autoWallpaperEnabled: Boolean = false,
    rearScreenOffTimeoutActive: Boolean = false,
    // 新增开关放在末尾：测试里有按位置传参的调用，插入中间会改变既有参数含义。
    faceEnrollmentCompat: Boolean = false,
    keepRearScreenOnInGame: Boolean = false,
    // 非校准机型不显示背屏电量显示开关，计数也要跟着排除。
    batteryRingSupported: Boolean = true,
): Int = listOf(
    disableLongPress,
    removeWallpaperLimit,
    removeAppCardLimit,
    fixRearScreenApply,
    disableRearScreenCover,
    disableDoubleTapWake,
    enable18ProFeatures,
    enableBatteryRing && batteryRingSupported,
    autoWallpaperEnabled,
    rearScreenOffTimeoutActive,
    faceEnrollmentCompat,
    keepRearScreenOnInGame,
).count { it }
