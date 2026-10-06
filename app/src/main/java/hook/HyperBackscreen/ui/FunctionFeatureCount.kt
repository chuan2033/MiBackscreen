package hook.HyperBackscreen.ui

/** Counts enabled switches reachable from Functions, not app selections or appearance options. */
internal fun countEnabledFunctions(
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    disableRearScreenCover: Boolean,
    disableDoubleTapWake: Boolean,
    enablePickup: Boolean,
    enable18ProFeatures: Boolean,
    enableBatteryRing: Boolean = false,
    // 非校准机型不显示背屏电量显示开关，计数也要跟着排除。
    batteryRingSupported: Boolean = true,
): Int = listOf(
    disableLongPress,
    removeWallpaperLimit,
    removeAppCardLimit,
    fixRearScreenApply,
    disableRearScreenCover,
    disableDoubleTapWake,
    enablePickup,
    enable18ProFeatures,
    enableBatteryRing && batteryRingSupported,
).count { it }
