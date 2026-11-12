package hook.HyperBackscreen.ui.function

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearWidgetList
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 背屏小部件的选择状态。两个页面和功能页入口共用一份；改动立即写模块偏好，
 * 并广播给背屏中心（[Constants.ACTION_SYNC_REAR_WIDGETS]），背屏页面列表马上跟着变，不用重启。
 */
internal object RearWidgetSelection {
    private val state = MutableStateFlow<List<String>?>(null)

    fun flow(context: Context): StateFlow<List<String>?> {
        if (state.value == null) state.value = RearWidgetList.parse(PrefsBridge.readRearAppWidgetsForUi(context))
        return state
    }

    fun add(context: Context, component: ComponentName): Boolean {
        val current = flow(context).value.orEmpty()
        val flat = component.flattenToString()
        if (flat in current || current.size >= RearWidgetList.MAX_WIDGETS) return false
        save(context, current + flat)
        return true
    }

    fun remove(context: Context, flat: String) {
        save(context, flow(context).value.orEmpty() - flat)
    }

    /** 恢复配置后：按偏好里的新值刷新本地状态并通知背屏中心。 */
    fun reload(context: Context) {
        save(context, RearWidgetList.parse(PrefsBridge.readRearAppWidgetsForUi(context)))
    }

    private fun save(context: Context, next: List<String>) {
        val encoded = RearWidgetList.encode(next)
        state.value = RearWidgetList.parse(encoded)
        PrefsBridge.writeRearAppWidgetsFromUi(context, encoded)
        context.sendBroadcast(
            Intent(Constants.ACTION_SYNC_REAR_WIDGETS)
                .setPackage(Constants.TARGET_PACKAGE)
                .putExtra(Constants.EXTRA_PREFERENCE_VALUE, encoded)
        )
    }
}

/** 一个可选的小部件。尺寸按桌面格子估算，只用于展示和「适合背屏」提示。 */
private class WidgetOption(
    val component: ComponentName,
    val label: String,
    val appLabel: String,
    val cellsWide: Int,
    val cellsHigh: Int,
    val preview: ImageBitmap?,
) {
    /** 背屏卡片是横的、不到 220dp 高：两格高以内、宽不小于高的小部件缩小后最清楚。 */
    val fitsRear: Boolean get() = cellsHigh <= 2 && cellsWide >= cellsHigh
}

private const val PREVIEW_SIZE_PX = 144

private fun loadOptions(context: Context, filter: (AppWidgetProviderInfo) -> Boolean): List<WidgetOption> {
    val pm = context.packageManager
    val density = context.resources.displayMetrics.density
    return AppWidgetManager.getInstance(context).installedProviders
        .asSequence()
        .filter(filter)
        .mapNotNull { info ->
            runCatching {
                val appLabel = pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                val drawable: Drawable? = info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0)
                WidgetOption(
                    component = info.provider,
                    label = info.loadLabel(pm),
                    appLabel = appLabel,
                    cellsWide = cells(info.targetCellWidth, info.minWidth / density),
                    cellsHigh = cells(info.targetCellHeight, info.minHeight / density),
                    preview = drawable?.let { scaledBitmap(it) },
                )
            }.getOrNull()
        }
        .sortedWith(compareBy({ it.appLabel }, { it.label }))
        .toList()
}

private fun cells(target: Int, minDp: Float): Int =
    if (target > 0) target else ((minDp + 30f) / 70f).toInt().coerceAtLeast(1)

private fun scaledBitmap(drawable: Drawable): ImageBitmap? = runCatching {
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: PREVIEW_SIZE_PX
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: PREVIEW_SIZE_PX
    val scale = PREVIEW_SIZE_PX.toFloat() / maxOf(w, h)
    drawable.toBitmap(
        (w * scale).toInt().coerceAtLeast(1),
        (h * scale).toInt().coerceAtLeast(1)
    ).asImageBitmap()
}.getOrNull()

/**
 * 需要先弹配置页的小部件放不上背屏：背屏中心每次创建页面都会新分配一个小部件 id，配置结果留不住。
 * 声明了「配置可选」的照常允许。
 */
private fun canPlaceOnRear(info: AppWidgetProviderInfo): Boolean =
    info.configure == null || info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0

/** 功能页入口的摘要。 */
@Composable
internal fun rearWidgetsEntrySummary(): String {
    val context = LocalContext.current
    val selected by remember(context) { RearWidgetSelection.flow(context) }.collectAsState()
    val count = selected.orEmpty().size
    return if (count == 0) stringResource(R.string.rear_widgets_summary_none)
    else pluralStringResource(R.plurals.rear_widgets_summary_count, count, count)
}

@Composable
private fun WidgetPreview(bitmap: ImageBitmap?) {
    if (bitmap == null) return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
    )
}

/** 「背屏小部件」二级页：已添加的小部件（可移除）+ 添加入口。 */
@Composable
internal fun RearWidgetsPage(onAddClick: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val selected by remember(context) { RearWidgetSelection.flow(context) }.collectAsState()
    val current = selected.orEmpty()
    val options by produceState<Map<String, WidgetOption>?>(null, current) {
        value = withContext(Dispatchers.IO) {
            val wanted = current.toSet()
            loadOptions(context) { it.provider.flattenToString() in wanted }
                .associateBy { it.component.flattenToString() }
        }
    }
    DetailPageScaffold(title = stringResource(R.string.rear_widgets_title), onBack = onBack, topSpacing = 0.dp) {
        item(key = "added") {
            SmallTitle(
                text = stringResource(R.string.rear_widgets_section_added),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                if (current.isEmpty()) {
                    BasicComponent(summary = stringResource(R.string.rear_widgets_empty))
                }
                current.forEach { flat ->
                    val option = options?.get(flat)
                    BasicComponent(
                        title = option?.label ?: flat.substringAfterLast('.'),
                        summary = option?.let { sizeSummary(it) }
                            ?: if (options != null) stringResource(R.string.rear_widgets_uninstalled) else null,
                        startAction = { WidgetPreview(option?.preview) },
                        endActions = {
                            val buttonBackdrop = rememberLayerBackdrop()
                            Box {
                                Box(
                                    Modifier.matchParentSize()
                                        .layerBackdrop(buttonBackdrop)
                                        .background(MiuixTheme.colorScheme.surfaceContainer)
                                )
                                GlassIconButton(
                                    onClick = { RearWidgetSelection.remove(context, flat) },
                                    backdrop = buttonBackdrop,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = stringResource(R.string.rear_widgets_remove),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                                    )
                                }
                            }
                        }
                    )
                }
                if (current.size < RearWidgetList.MAX_WIDGETS) {
                    ArrowPreference(
                        title = stringResource(R.string.rear_widgets_add),
                        onClick = onAddClick
                    )
                }
            }
            BasicComponent(
                summary = stringResource(R.string.rear_widgets_hint, RearWidgetList.MAX_WIDGETS)
            )
        }
    }
}

@Composable
private fun sizeSummary(option: WidgetOption): String =
    stringResource(R.string.rear_widgets_option_summary, option.appLabel, option.cellsWide, option.cellsHigh)

/** 选择页：按应用分组列出能放上背屏的小部件，点一个就添加并返回。 */
@Composable
internal fun RearWidgetPickerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val selected by remember(context) { RearWidgetSelection.flow(context) }.collectAsState()
    val groups by produceState<Map<String, List<WidgetOption>>?>(null) {
        value = withContext(Dispatchers.IO) {
            loadOptions(context, ::canPlaceOnRear).groupBy { it.appLabel }
        }
    }
    DetailPageScaffold(title = stringResource(R.string.rear_widgets_add), onBack = onBack, topSpacing = 0.dp) {
        val loaded = groups
        if (loaded == null) {
            item(key = "loading") {
                BasicComponent(summary = stringResource(R.string.rear_widgets_loading))
            }
            return@DetailPageScaffold
        }
        loaded.forEach { (app, options) ->
            item(key = "app_$app") {
                SmallTitle(text = app, insideMargin = PaddingValues(16.dp, 8.dp))
                CardBlock(pressFeedbackType = PressFeedbackType.None) {
                    options.forEach { option ->
                        val flat = option.component.flattenToString()
                        val added = flat in selected.orEmpty()
                        BasicComponent(
                            title = option.label,
                            summary = if (added) stringResource(R.string.rear_widgets_already_added)
                            else stringResource(
                                if (option.fitsRear) R.string.rear_widgets_cells_fit else R.string.rear_widgets_cells,
                                option.cellsWide, option.cellsHigh
                            ),
                            startAction = { WidgetPreview(option.preview) },
                            enabled = !added,
                            onClick = {
                                if (RearWidgetSelection.add(context, option.component)) onBack()
                            }
                        )
                    }
                }
            }
        }
    }
}
