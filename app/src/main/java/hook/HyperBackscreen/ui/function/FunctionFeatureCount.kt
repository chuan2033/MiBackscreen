package hook.HyperBackscreen.ui.function

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
    // 两份新名单：非空即视为启用。
    doubleTapSleepFilterActive: Boolean = false,
    musicWidgetWhitelistActive: Boolean = false,
    // 背屏视频壁纸三项与背屏实时歌词。
    rearMedia: RearMediaSwitches = RearMediaSwitches(),
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
    doubleTapSleepFilterActive,
    musicWidgetWhitelistActive,
    rearMedia.videoResume,
    rearMedia.videoUnlockLimits,
    rearMedia.videoKeepAudio,
    rearMedia.rearLyric,
).count { it }

/**
 * 功能页三个折叠分组各自开着几项，显示在分组标题行右侧，折叠时也能看出哪组有东西开着。
 * 归属与 FunctionPage 的分组一致；18 Pro 总开关不在任何分组里，不计入。
 */
internal data class FunctionGroupCounts(
    val system: Int = 0,
    val subscreen: Int = 0,
    val theme: Int = 0,
)

internal fun countFunctionGroups(
    disableRearScreenCover: Boolean,
    disableDoubleTapWake: Boolean,
    enableBatteryRing: Boolean,
    batteryRingSupported: Boolean,
    disableLongPress: Boolean,
    removeAppCardLimit: Boolean,
    autoWallpaperEnabled: Boolean,
    rearScreenOffTimeoutActive: Boolean,
    keepRearScreenOnInGame: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    removeWallpaperLimit: Boolean,
    doubleTapSleepFilterActive: Boolean = false,
    musicWidgetWhitelistActive: Boolean = false,
    rearMedia: RearMediaSwitches = RearMediaSwitches(),
): FunctionGroupCounts = FunctionGroupCounts(
    system = listOf(disableRearScreenCover, disableDoubleTapWake, doubleTapSleepFilterActive).count { it },
    subscreen = listOf(
        enableBatteryRing && batteryRingSupported,
        disableLongPress,
        removeAppCardLimit,
        autoWallpaperEnabled,
        rearScreenOffTimeoutActive,
        keepRearScreenOnInGame,
        musicWidgetWhitelistActive,
        rearMedia.rearLyric,
    ).count { it },
    // 视频壁纸三项放在「主题壁纸」组里显示（续播的 Hook 在背屏中心，但入口和壁纸设置放在一起）。
    theme = listOf(
        fixRearScreenApply,
        faceEnrollmentCompat,
        removeWallpaperLimit,
        rearMedia.videoResume,
        rearMedia.videoUnlockLimits,
        rearMedia.videoKeepAudio,
    ).count { it },
)

/** 背屏视频壁纸三项与背屏实时歌词的开关值，一起在界面层级间传递。 */
internal data class RearMediaSwitches(
    val videoResume: Boolean = false,
    val videoUnlockLimits: Boolean = false,
    val videoKeepAudio: Boolean = false,
    val rearLyric: Boolean = false,
)

/** [RearMediaSwitches] 里的单项，用于开关回调。 */
internal enum class RearMediaSwitch {
    VIDEO_RESUME,
    VIDEO_UNLOCK_LIMITS,
    VIDEO_KEEP_AUDIO,
    REAR_LYRIC,
}
