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
): Int = listOf(
    disableLongPress,
    removeWallpaperLimit,
    removeAppCardLimit,
    fixRearScreenApply,
    disableRearScreenCover,
    disableDoubleTapWake,
    enablePickup,
).count { it }
