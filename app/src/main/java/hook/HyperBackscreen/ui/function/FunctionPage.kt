package hook.HyperBackscreen.ui.function

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R

import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.batteryring.BatteryRingGeometry
import hook.HyperBackscreen.lyric.SuperLyricAvailability
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.CollapsibleFunctionGroup
import hook.HyperBackscreen.ui.components.SettingsInfoRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun FunctionPage(
    groupCounts: FunctionGroupCounts,
    onBatteryColorClick: () -> Unit,
    onRearScreenTimingClick: () -> Unit,
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onRearDoubleTapClick: () -> Unit,
    doubleTapSleepDisabledPackages: String,
    musicWidgetWhitelistPackages: String,
    onMusicWidgetWhitelistClick: () -> Unit,
    onRearScreenRecordClick: () -> Unit,
    rearMedia: RearMediaSwitches = RearMediaSwitches(),
    onRearMediaChange: (RearMediaSwitch, Boolean) -> Unit = { _, _ -> },
) {
    val batteryRingSupported = BatteryRingGeometry.isCalibratedDevice(Build.DEVICE)
    Column {
        // 跨作用域的总开关：同时涉及主题商店、背屏中心与智能助理，不归入任何单个分组。
        CardBlock(pressFeedbackType = PressFeedbackType.None) {
            SwitchPreference(
                checked = enable18ProFeatures,
                onCheckedChange = onEnable18ProFeaturesChange,
                title = stringResource(R.string.home_enable_18_pro_features_title)
            )
        }

        // 下列分组与 Xposed 作用域一一对应：系统框架、背屏中心、主题商店、超级小爱。
        // 四组共用同一个卡片容器，展开内容在容器内展开。
        CardBlock(pressFeedbackType = PressFeedbackType.None) {
            CollapsibleFunctionGroup(
                title = stringResource(R.string.function_group_system),
                iconRes = R.drawable.group_system,
                enabledCount = groupCounts.system,
                persistKey = "system"
            ) {
                SwitchPreference(
                    checked = disableRearScreenCover,
                    onCheckedChange = onDisableRearScreenCoverChange,
                    title = stringResource(R.string.config_disable_rear_cover_title),
                    summary = stringResource(R.string.config_disable_rear_cover_summary)
                )
                // 双击亮屏 / 双击熄屏两份名单合在「背屏双击」页里，这里只留一个入口。
                ArrowPreference(
                    title = stringResource(R.string.rear_double_tap_title),
                    summary = rearDoubleTapEntrySummary(
                        PackageListCodec.parse(doubleTapWakeDisabledPackages).size,
                        PackageListCodec.parse(doubleTapSleepDisabledPackages).size,
                    ),
                    onClick = onRearDoubleTapClick
                )
            }

            CollapsibleFunctionGroup(
                title = stringResource(R.string.function_group_subscreen),
                iconRes = R.drawable.group_subscreen,
                enabledCount = groupCounts.subscreen,
                persistKey = "subscreen"
            ) {
                // 几何按校准机型实测，其他机型背屏不绘制，入口也不显示。
                // 电量总开关已经收到二级页里，这里只留常驻入口。
                if (batteryRingSupported) {
                    ArrowPreference(
                        title = stringResource(R.string.function_battery_color_title),
                        onClick = onBatteryColorClick
                    )
                }
                SwitchPreference(
                    checked = disableLongPress,
                    onCheckedChange = onDisableLongPressChange,
                    title = stringResource(R.string.home_disable_long_press_title)
                )
                SwitchPreference(
                    checked = removeAppCardLimit,
                    onCheckedChange = onRemoveAppCardLimitChange,
                    title = stringResource(R.string.home_remove_app_card_limit_title)
                )
                // 息屏档位、两个定时开关、游戏时保持常亮都收进二级页，这里只留入口。
                ArrowPreference(
                    title = stringResource(R.string.rear_screen_timing_title),
                    onClick = onRearScreenTimingClick
                )
                val musicAppCount = PackageListCodec.parse(musicWidgetWhitelistPackages).size
                ArrowPreference(
                    title = stringResource(R.string.music_widget_whitelist_title),
                    summary = if (musicAppCount == 0) {
                        stringResource(R.string.music_widget_whitelist_summary_none)
                    } else {
                        pluralStringResource(
                            R.plurals.music_widget_whitelist_summary_count,
                            musicAppCount,
                            musicAppCount
                        )
                    },
                    onClick = onMusicWidgetWhitelistClick
                )
                SwitchPreference(
                    checked = rearMedia.rearLyric,
                    onCheckedChange = { onRearMediaChange(RearMediaSwitch.REAR_LYRIC, it) },
                    title = stringResource(R.string.rear_lyric_title),
                    summary = stringResource(R.string.rear_lyric_summary)
                )
                AnimatedPreferenceVisibility(visible = rearMedia.rearLyric) {
                    SuperLyricStatusRow(active = rearMedia.rearLyric)
                }
            }

            CollapsibleFunctionGroup(
                title = stringResource(R.string.function_group_theme),
                iconRes = R.drawable.group_theme,
                enabledCount = groupCounts.theme,
                persistKey = "theme"
            ) {
                SwitchPreference(
                    checked = fixRearScreenApply,
                    onCheckedChange = onFixRearScreenApplyChange,
                    title = stringResource(R.string.home_fix_apply_title),
                    summary = stringResource(R.string.home_fix_apply_summary)
                )
                SwitchPreference(
                    checked = faceEnrollmentCompat,
                    onCheckedChange = onFaceEnrollmentCompatChange,
                    title = stringResource(R.string.face_enrollment_compat_title),
                    summary = stringResource(R.string.face_enrollment_compat_summary)
                )
                SwitchPreference(
                    checked = removeWallpaperLimit,
                    onCheckedChange = onRemoveWallpaperLimitChange,
                    title = stringResource(R.string.home_remove_wallpaper_limit_title)
                )
                SwitchPreference(
                    checked = rearMedia.videoResume,
                    onCheckedChange = { onRearMediaChange(RearMediaSwitch.VIDEO_RESUME, it) },
                    title = stringResource(R.string.rear_video_resume_title),
                    summary = stringResource(R.string.rear_video_resume_summary)
                )
                SwitchPreference(
                    checked = rearMedia.videoUnlockLimits,
                    onCheckedChange = { onRearMediaChange(RearMediaSwitch.VIDEO_UNLOCK_LIMITS, it) },
                    title = stringResource(R.string.rear_video_unlock_limits_title),
                    summary = stringResource(R.string.rear_video_unlock_limits_summary)
                )
                SwitchPreference(
                    checked = rearMedia.videoKeepAudio,
                    onCheckedChange = { onRearMediaChange(RearMediaSwitch.VIDEO_KEEP_AUDIO, it) },
                    title = stringResource(R.string.rear_video_keep_audio_title),
                    summary = stringResource(R.string.rear_video_keep_audio_summary)
                )
            }
        }

        // 背屏工具：模块 App 自己用 root 完成的动作，不对应任何 Xposed 作用域，单独一张卡片。
        CardBlock(pressFeedbackType = PressFeedbackType.None) {
            ArrowPreference(
                title = stringResource(R.string.rear_record_title),
                summary = stringResource(R.string.rear_record_entry_summary),
                onClick = onRearScreenRecordClick
            )
        }
    }
}

/** 歌词开关下的 SuperLyric 状态：模块 App 进程里能拿到服务，背屏中心里也一定能拿到。 */
@Composable
private fun SuperLyricStatusRow(active: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<SuperLyricAvailability?>(null) }
    // 每次打开开关重新检测一次（用户可能刚去 LSPosed 勾完作用域回来）；检测有 Binder 调用，放 IO 线程。
    LaunchedEffect(active) {
        if (active) {
            status = null
            status = withContext(Dispatchers.IO) {
                runCatching { SuperLyricAvailability.detect(context.applicationContext) }.getOrNull()
                    ?: SuperLyricAvailability.SERVICE_UNAVAILABLE
            }
        }
    }
    SettingsInfoRow(
        label = stringResource(R.string.rear_lyric_status_label),
        value = stringResource(
            when (status) {
                null -> R.string.rear_lyric_status_checking
                SuperLyricAvailability.V4 -> R.string.rear_lyric_status_v4
                SuperLyricAvailability.LEGACY -> R.string.rear_lyric_status_legacy
                SuperLyricAvailability.SERVICE_UNAVAILABLE -> R.string.rear_lyric_status_unavailable
                SuperLyricAvailability.NOT_INSTALLED -> R.string.rear_lyric_status_not_installed
            }
        )
    )
}
