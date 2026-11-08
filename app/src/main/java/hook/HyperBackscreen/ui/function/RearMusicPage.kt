package hook.HyperBackscreen.ui.function

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.lyric.SuperLyricAvailability
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.SettingsInfoRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 「背屏音乐」二级页：音乐卡片白名单 + 实时歌词（开关、SuperLyric 状态、息屏显示）。 */
@Composable
internal fun RearMusicPage(
    musicWidgetWhitelistPackages: String,
    rearMedia: RearMediaSwitches,
    onRearMediaChange: (RearMediaSwitch, Boolean) -> Unit,
    onWhitelistClick: () -> Unit,
    onBack: () -> Unit,
) {
    DetailPageScaffold(
        title = stringResource(R.string.rear_music_title),
        onBack = onBack,
        topSpacing = 0.dp,
    ) {
        item(key = "card") {
            SmallTitle(
                text = stringResource(R.string.rear_music_section_card),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                ArrowPreference(
                    title = stringResource(R.string.music_widget_whitelist_title),
                    summary = musicWhitelistSummary(musicWidgetWhitelistPackages),
                    onClick = onWhitelistClick
                )
            }
        }
        item(key = "lyric") {
            SmallTitle(
                text = stringResource(R.string.rear_music_section_lyric),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                SwitchPreference(
                    checked = rearMedia.rearLyric,
                    onCheckedChange = { onRearMediaChange(RearMediaSwitch.REAR_LYRIC, it) },
                    title = stringResource(R.string.rear_lyric_title),
                    summary = stringResource(R.string.rear_lyric_summary)
                )
                AnimatedPreferenceVisibility(visible = rearMedia.rearLyric) {
                    Column {
                        SuperLyricStatusRow(active = rearMedia.rearLyric)
                        SwitchPreference(
                            checked = rearMedia.rearLyricInAod,
                            onCheckedChange = { onRearMediaChange(RearMediaSwitch.REAR_LYRIC_IN_AOD, it) },
                            title = stringResource(R.string.rear_lyric_in_aod_title),
                            summary = stringResource(R.string.rear_lyric_in_aod_summary)
                        )
                    }
                }
            }
        }
    }
}

/** 功能页「背屏音乐」入口的摘要：实时歌词开没开、白名单追加了几个应用。 */
@Composable
internal fun rearMusicEntrySummary(rearLyric: Boolean, musicWidgetWhitelistPackages: String): String {
    val lyric = stringResource(if (rearLyric) R.string.rear_music_summary_lyric_on else R.string.rear_music_summary_lyric_off)
    val count = PackageListCodec.parse(musicWidgetWhitelistPackages).size
    if (count == 0) return lyric
    return lyric + " · " + pluralStringResource(R.plurals.music_widget_whitelist_summary_count, count, count)
}

@Composable
private fun musicWhitelistSummary(packages: String): String {
    val count = PackageListCodec.parse(packages).size
    return if (count == 0) {
        stringResource(R.string.music_widget_whitelist_summary_none)
    } else {
        pluralStringResource(R.plurals.music_widget_whitelist_summary_count, count, count)
    }
}

/**
 * 歌词开关下的 SuperLyric 状态：模块 App 进程里能拿到服务，背屏中心里也一定能拿到。
 * 没装 / 没生效时在下面补一行怎么配置，正常连接时不占地方。
 */
@Composable
private fun ColumnScope.SuperLyricStatusRow(active: Boolean) {
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
    val needsSetup = status == SuperLyricAvailability.SERVICE_UNAVAILABLE ||
        status == SuperLyricAvailability.NOT_INSTALLED
    AnimatedPreferenceVisibility(visible = needsSetup) {
        BasicComponent(summary = stringResource(R.string.rear_lyric_setup_hint))
    }
}
