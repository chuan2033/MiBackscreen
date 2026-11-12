package hook.HyperBackscreen.ui.battery

import hook.HyperBackscreen.ui.components.AppDialog
import hook.HyperBackscreen.batteryring.BatteryColorOverrides
import hook.HyperBackscreen.batteryring.BatteryPercentPosition
import hook.HyperBackscreen.batteryring.BatteryRingGeometry
import hook.HyperBackscreen.batteryring.BatteryRingLayout
import hook.HyperBackscreen.batteryring.BatteryRingStyle
import hook.HyperBackscreen.batteryring.CHARGE_SWEEP_DURATION_MS
import hook.HyperBackscreen.batteryring.applyPercentShadow
import hook.HyperBackscreen.batteryring.chargeSweepColor
import hook.HyperBackscreen.batteryring.chargeSweepSegments
import hook.HyperBackscreen.batteryring.formatBatteryColor
import hook.HyperBackscreen.batteryring.parseBatteryColor
import hook.HyperBackscreen.batteryring.ringProgressStops
import hook.HyperBackscreen.batteryring.trackColorOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DialogButtonRow
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.AppGlassTabRow
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import top.yukonga.miuix.kmp.basic.Button
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.CollapsibleFunctionGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 电量状态分段控件上下的留白。
 *
 * 分段玻璃条紧贴上方折叠标题行和下方色盘时会显得拥挤，
 * 所以单独留出这一段。加上标题行自身的 16dp 内边距后，上下各约 24dp，视觉对称。
 */
private val BatteryColorTabRowVerticalMargin = 8.dp

/**
 * 数字电量可选的两个位置。
 *
 * 「不显示」不在这里——它是开关的关态，不是第三个位置；混在一起会让同一个控件既管显隐又管左右。
 */
private val BatteryPercentPositionTabs = listOf(
    BatteryPercentPosition.LEFT,
    BatteryPercentPosition.RIGHT
)

/** 可单独设置颜色的三个电量状态。 */
internal enum class BatteryColorSlot(
    /** SystemUI 电池资源名，跟随系统时按它取值。 */
    val systemColorRole: String,
    /** 系统资源读取失败时的回落值，与 [BatteryRingState.fallbackColor] 保持一致。 */
    val fallbackColor: Int,
    @get:StringRes val labelRes: Int,
) {
    IDLE("status_bar_battery_level_white", 0xFFFFFFFF.toInt(), R.string.battery_color_state_idle),
    CHARGING("status_bar_battery_charging", 0xFF1DCD3A.toInt(), R.string.battery_color_state_charging),
    LOW("status_bar_battery_low", 0xFFFA382E.toInt(), R.string.battery_color_state_low),
}

/** 预览用的代表电量，只影响进度线长度。 */
private val BatteryColorSlot.previewPercent: Int
    get() = when (this) {
        BatteryColorSlot.IDLE -> 82
        BatteryColorSlot.CHARGING -> 64
        BatteryColorSlot.LOW -> 14
    }

private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

/** 预览里镜头与电量线相对面板的右移量，避免线条左侧贴边。 */
private const val PreviewContentOffsetX = 10f

/** 轮询背屏壁纸变化的间隔：只做文件时间戳比较，换壁纸后一两秒内预览就会跟上。 */
private const val WallpaperPollIntervalMillis = 1500L

@Composable
internal fun BatteryColorPage(
    enableBatteryRing: Boolean,
    onEnableBatteryRingChange: (Boolean) -> Unit,
    /** 电量环三态颜色，空串 = 跟随系统。 */
    ringColors: BatteryColorOverrides,
    /** 百分比数字三态颜色，空串 = 跟随电量环。 */
    percentColors: BatteryColorOverrides,
    percentPosition: BatteryPercentPosition,
    layout: BatteryRingLayout,
    onColorChange: (BatteryColorTarget, BatteryColorSlot, String) -> Unit,
    onPercentPositionChange: (BatteryPercentPosition) -> Unit,
    onLayoutCommit: (BatteryRingLayout) -> Unit,
    onBack: () -> Unit
) {
    // 预览用的电量与充电状态。当前在改哪个状态的颜色由它推出来（与背屏同一判据），
    // 所以拖预览电量到 20% 以下就自动切到「低电量」色，点状态标签则把预览跳到该状态的代表电量。
    var previewPercent by remember { mutableIntStateOf(BatteryColorSlot.IDLE.previewPercent) }
    var previewCharging by remember { mutableStateOf(false) }
    // 拖动时只改草稿让预览跟手，松手才落偏好：滑杆每帧回调，逐帧写偏好会连带触发
    // 偏好监听 → 重组，等于自己给自己喂事件。
    var draft by remember { mutableStateOf(layout) }
    LaunchedEffect(layout) { draft = layout }
    // 低电量判据用用户设置的阈值（草稿值，拖阈值滑杆时预览即时跟着变色）。
    val lowThreshold = draft.lowThresholdPercent
    val selected = when {
        previewCharging -> BatteryColorSlot.CHARGING
        previewPercent < lowThreshold -> BatteryColorSlot.LOW
        else -> BatteryColorSlot.IDLE
    }
    fun previewSlot(slot: BatteryColorSlot) {
        // 低电量的代表电量要落在当前阈值之下，否则阈值调低后点「低电量」反而显示成未充电色。
        previewPercent = if (slot == BatteryColorSlot.LOW) {
            minOf(slot.previewPercent, lowThreshold - 1).coerceAtLeast(1)
        } else {
            slot.previewPercent
        }
        previewCharging = slot == BatteryColorSlot.CHARGING
    }
    // 点滑杆的标题行弹出的精确输入（滑杆用来快速试，弹窗用来把数值定死）。
    // 状态放在页面级：两个折叠里的滑杆共用同一个弹窗，不各挂一份实例。
    var inputField by remember { mutableStateOf<BatteryLayoutField?>(null) }
    var inputVisible by remember { mutableStateOf(false) }
    // 关掉开关再打开时回到上次选的那一侧，「不显示」本身不是位置，没得记就默认右侧。
    var lastShownPosition by rememberSaveable { mutableStateOf(BatteryPercentPosition.RIGHT) }
    LaunchedEffect(percentPosition) {
        if (percentPosition != BatteryPercentPosition.OFF) lastShownPosition = percentPosition
    }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    // 与绘制端同一判据：几何只在校准机型上成立。
    val rearScreenGeometry = remember { BatteryRingGeometry.forDevice(Build.DEVICE) }
    val rearScreenSupported = rearScreenGeometry != null

    if (rearScreenSupported) {
        // 背屏当前壁纸的真实截图。壁纸随时可能在背屏上被切换或重新截图，这里按文件指纹轮询，
        // 变化时重新解码；读取失败保留上一张，预览在完全没有截图时回退到纯色面板。
        // 只在页面可见（RESUMED）时轮询：退到后台或被别的页面盖住就停，回来再续；
        // 指纹放在循环外，恢复轮询时壁纸没变就不会重复解码。
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            var lastStamp: String? = null
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val source = withContext(Dispatchers.IO) { RearScreenWallpaper.resolve() }
                    if (source != null && source.stamp != lastStamp) {
                        val image = withContext(Dispatchers.IO) { RearScreenWallpaper.decode(source) }
                        if (image != null) {
                            wallpaper = image
                            lastStamp = source.stamp
                        }
                    }
                    delay(WallpaperPollIntervalMillis)
                }
            }
        }
    }

    // 正在给谁上色：电量环或百分比数字。只是编辑焦点，不写偏好；状态（未充电 / 充电中 / 低电量）两者共用。
    var colorTarget by rememberSaveable { mutableStateOf(BatteryColorTarget.RING) }
    val ringColor = resolveSlotColor(selected, ringColors.rawFor(selected))
    val percentColor = resolvePercentColor(percentColors.rawFor(selected), ringColor)

    DetailPageScaffold(
        title = stringResource(R.string.function_battery_color_title),
        onBack = onBack,
        // 预览固定在顶栏下方：展开色盘、拖滑杆时都能直接看到背屏效果。
        pinnedHeader = rearScreenGeometry?.let { geometry ->
            {
                BatteryRearScreenPreview(
                    geometry = geometry,
                    percent = previewPercent,
                    charging = previewCharging,
                    color = ringColor,
                    percentColor = percentColor,
                    percentPosition = percentPosition,
                    layout = draft,
                    wallpaper = wallpaper,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        },
    ) {
        // 几何只在校准机型上成立，其他机型背屏不绘制，这里也不给可调的假预览。
        if (!rearScreenSupported) {
            item(key = "unsupported_model") {
                CardBlock(pressFeedbackType = PressFeedbackType.None) {
                    Text(
                        text = stringResource(R.string.battery_color_unsupported_model),
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            return@DetailPageScaffold
        }
        item(key = "preview_controls") {
            // 只影响预览，不写任何偏好：用来检查低电量配色、双环在 50% 处的衔接、充电流光等。
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                SliderPreference(
                    value = previewPercent.toFloat(),
                    onValueChange = { previewPercent = it.roundToInt().coerceIn(0, 100) },
                    title = stringResource(R.string.battery_preview_percent_title),
                    valueText = "$previewPercent%",
                    valueRange = 0f..100f
                )
                SwitchPreference(
                    checked = previewCharging,
                    onCheckedChange = { previewCharging = it },
                    title = stringResource(R.string.battery_preview_charging_title)
                )
            }
        }
        // 三个折叠分组各占一张卡：展开其中一个（尤其是很长的色盘）时，其它分组不会被一起撑长。
        item(key = "ring_card") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                // 电量环折叠：这圈线显不显示、有没有底轨、多大多粗。颜色单独在下面一格。
                CollapsibleFunctionGroup(
                    title = stringResource(R.string.battery_color_state_title)
                ) {
                    // 总开关从功能页搬进来，摆法与「数字电量」里的显隐开关一致：
                    // 都放在折叠内第一行。
                    SwitchPreference(
                        checked = enableBatteryRing,
                        onCheckedChange = onEnableBatteryRingChange,
                        title = stringResource(R.string.battery_enable_title)
                    )
                    // 线条样式：绕模组一整圈的连体轮廓，或上下各一圈的双环。
                    // 摆法与颜色那格一致，两个选项落在同一条底色上；样式只换绘制轨道，
                    // 下面的尺寸参数两种样式共用，切过去不用重调粗细。
                    AppGlassTabRow(
                        tabs = BatteryRingStyle.entries.map { stringResource(it.labelRes) },
                        selectedIndex = draft.ringStyle.ordinal,
                        onSelect = { index ->
                            BatteryRingStyle.entries.getOrNull(index)?.let { style ->
                                val next = draft.copy(ringStyle = style)
                                draft = next
                                onLayoutCommit(next)
                            }
                        },
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                        segmented = true,
                        modifier = Modifier.fillMaxWidth().padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = BatteryColorTabRowVerticalMargin,
                            bottom = BatteryColorTabRowVerticalMargin
                        )
                    )
                    // 线条自身的开关与尺寸（底轨、大小、粗细）都归这一组，
                    // 不再单占一张卡片。
                    BatteryLayoutFieldGroup(
                        fields = BatteryRingFields,
                        draft = draft,
                        percentPosition = percentPosition,
                        onDraftChange = { draft = it },
                        onCommit = onLayoutCommit,
                        // 样式不在 fields 里，但「恢复默认」也应该把它带回连体轮廓。
                        resetExtra = { layout ->
                            layout.copy(ringStyle = BatteryRingStyle.DEFAULT)
                        },
                        onEditRequest = { field ->
                            inputField = field
                            inputVisible = true
                        }
                    )
                }
            }
        }
        item(key = "percent_card") {
                // 数字电量：显隐拆成独立开关，位置用分段控件（与其它几处同一种），
                // 数字自己的位置微调一并收进这个折叠，免得散在另一张卡片上。
                val percentShown = percentPosition != BatteryPercentPosition.OFF

                fun applyPosition(position: BatteryPercentPosition) {
                    // 水平偏移左右各存一份，切换后由持有方按新位置重算 layout 回灌，
                    // 这里不要再拿当前位置去改写偏移 —— 之前那样会把另一侧刚调好的值冲掉。
                    onPercentPositionChange(position)
                }

            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                CollapsibleFunctionGroup(
                    title = stringResource(R.string.battery_percent_position_title)
                ) {
                    SwitchPreference(
                        checked = percentShown,
                        onCheckedChange = { show ->
                            applyPosition(
                                if (show) lastShownPosition else BatteryPercentPosition.OFF
                            )
                        },
                        title = stringResource(R.string.battery_percent_show_title)
                    )
                    AnimatedVisibility(visible = percentShown) {
                        // AnimatedVisibility 只有一个内容槽，多行要自己包一层。
                        Column {
                            AppGlassTabRow(
                                tabs = BatteryPercentPositionTabs.map {
                                    stringResource(it.labelRes)
                                },
                                selectedIndex = BatteryPercentPositionTabs
                                    .indexOf(percentPosition)
                                    .coerceAtLeast(0),
                                onSelect = { index ->
                                    BatteryPercentPositionTabs.getOrNull(index)
                                        ?.let { position -> applyPosition(position) }
                                },
                                backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                                segmented = true,
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = BatteryColorTabRowVerticalMargin,
                                    bottom = BatteryColorTabRowVerticalMargin
                                )
                            )
                            BatteryLayoutFieldGroup(
                                fields = BatteryPercentFields,
                                draft = draft,
                                percentPosition = percentPosition,
                                onDraftChange = { draft = it },
                                onCommit = onLayoutCommit,
                                onEditRequest = { field ->
                                    inputField = field
                                    inputVisible = true
                                }
                            )
                        }
                    }
                }
            }
        }
        item(key = "color_card") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                // 颜色自定义排在最下面：色盘展开后很长，夹在中间会把「数字电量」
                // 顶到屏幕外，想调数字位置得先划过整块调色区。放末尾就不挡事。
                // 「选哪个状态」本身就是「给谁上色」，跟调色盘是一件事，所以状态
                // 颜色状态选项与色盘放在同一张卡片，展开时可以直接看到当前编辑的状态。
                // 玻璃分段条使用滑动指示器，左右缩进与卡片内容一致。
                CollapsibleFunctionGroup(
                    title = stringResource(R.string.battery_color_custom_title)
                ) {
                    // 先选给谁上色（环 / 数字），再选哪个状态：两排分段条同一种样式，从上往下读。
                    AppGlassTabRow(
                        tabs = BatteryColorTarget.entries.map { stringResource(it.labelRes) },
                        selectedIndex = colorTarget.ordinal,
                        onSelect = { index ->
                            BatteryColorTarget.entries.getOrNull(index)?.let { colorTarget = it }
                        },
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                        segmented = true,
                        modifier = Modifier.fillMaxWidth().padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = BatteryColorTabRowVerticalMargin,
                            bottom = BatteryColorTabRowVerticalMargin
                        )
                    )
                    AppGlassTabRow(
                        tabs = BatteryColorSlot.entries.map { stringResource(it.labelRes) },
                        selectedIndex = selected.ordinal,
                        onSelect = { index ->
                            BatteryColorSlot.entries.getOrNull(index)?.let { previewSlot(it) }
                        },
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                        segmented = true,
                        modifier = Modifier.fillMaxWidth().padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = BatteryColorTabRowVerticalMargin,
                            bottom = BatteryColorTabRowVerticalMargin
                        )
                    )
                    // 低电量从多少开始算：和「低电量」颜色是一件事，放在颜色这一格。
                    BatteryLayoutFieldGroup(
                        fields = BatteryColorFields,
                        draft = draft,
                        percentPosition = percentPosition,
                        onDraftChange = { draft = it },
                        onCommit = onLayoutCommit,
                        onEditRequest = { field ->
                            inputField = field
                            inputVisible = true
                        },
                        showReset = false
                    )
                    when (colorTarget) {
                        BatteryColorTarget.RING -> BatteryColorEditor(
                            editKey = colorTarget to selected,
                            color = ringColor,
                            resetLabel = stringResource(R.string.battery_color_reset_system),
                            resetValue = "",
                            resetArgb = resolveSlotColor(selected, "").toArgb(),
                            onColorChange = { value ->
                                onColorChange(BatteryColorTarget.RING, selected, value)
                            }
                        )
                        BatteryColorTarget.PERCENT -> BatteryPercentColorSection(
                            slot = selected,
                            raw = percentColors.rawFor(selected),
                            color = percentColor,
                            percentShown = percentPosition != BatteryPercentPosition.OFF,
                            onColorChange = { value ->
                                onColorChange(BatteryColorTarget.PERCENT, selected, value)
                            }
                        )
                    }
                }
            }
        }
    }

    // 精确输入弹窗放在页面级：两个折叠里的滑杆共用它，页面上同时只挂一个弹窗实例。
    val active = inputField
    AppDialog(
        show = inputVisible,
        title = active?.let { stringResource(it.titleRes) }.orEmpty(),
        summary = active?.let { field ->
            val range = field.rangeFor(percentPosition)
            stringResource(
                R.string.battery_layout_dialog_summary,
                field.format(field.defaultValueFor(percentPosition)),
                field.format(range.first),
                field.format(range.last)
            )
        },
        onDismissRequest = { inputVisible = false },
        // 关闭动画跑完再清内容：中途又被点开时 Miuix 不会回调，标题不会闪空。
        onDismissFinished = { inputField = null }
    ) {
        active?.let { field ->
            BatteryLayoutInputDialog(
                field = field,
                initialValue = field.read(draft),
                defaultValue = field.defaultValueFor(percentPosition),
                range = field.rangeFor(percentPosition),
                onApply = { value ->
                    val next = field.write(draft, value)
                    draft = next
                    onLayoutCommit(next)
                    inputVisible = false
                },
                onCancel = { inputVisible = false }
            )
        }
    }
}

/** 可调项的数值单位，决定滑杆尾部的显示格式。 */
private enum class BatteryLayoutUnit {
    /** 偏移量：带符号（+0 没有意义，按 0 处理），单位百分比。 */
    OFFSET,

    /** 百分比，不带符号。 */
    PERCENT,

    /** 背屏像素，直接显示数值。 */
    PIXEL,

    /** 字重（100~900），无量纲，直接显示数值。 */
    WEIGHT,
}

/**
 * 电量自定义里的可调项：滑杆、开关与弹窗输入共用这一份元数据，避免几处各写一遍范围。
 *
 * 各项按归属分成两组，分别落在「电量环」和「数字电量」两个折叠里，
 * 各自带一个只还原自己那组的「恢复默认」。
 */
private enum class BatteryLayoutField(
    @get:StringRes val titleRes: Int,
    val unit: BatteryLayoutUnit,
    /** 布尔项：渲染成开关而不是滑杆，也不参与精确输入弹窗。 */
    val toggle: Boolean = false,
) {
    RING_TRACK(R.string.battery_ring_track_title, BatteryLayoutUnit.PIXEL, toggle = true),
    CHARGE_SWEEP(R.string.battery_charge_sweep_title, BatteryLayoutUnit.PIXEL, toggle = true),
    PERCENT_SHADOW(R.string.battery_percent_shadow_title, BatteryLayoutUnit.PIXEL, toggle = true),
    RING_SCALE(R.string.battery_ring_scale_title, BatteryLayoutUnit.PERCENT),
    RING_STROKE(R.string.battery_ring_stroke_title, BatteryLayoutUnit.PIXEL),
    PERCENT_X(R.string.battery_percent_offset_x_title, BatteryLayoutUnit.OFFSET),
    PERCENT_Y(R.string.battery_percent_offset_y_title, BatteryLayoutUnit.OFFSET),
    PERCENT_TEXT_SIZE(R.string.battery_percent_text_size_title, BatteryLayoutUnit.PIXEL),
    PERCENT_TEXT_WEIGHT(R.string.battery_percent_text_weight_title, BatteryLayoutUnit.WEIGHT),
    LOW_THRESHOLD(R.string.battery_low_threshold_title, BatteryLayoutUnit.PERCENT);

    fun read(layout: BatteryRingLayout): Int = when (this) {
        RING_TRACK -> if (layout.ringTrackVisible) 1 else 0
        CHARGE_SWEEP -> if (layout.chargeSweepEnabled) 1 else 0
        PERCENT_SHADOW -> if (layout.percentShadowEnabled) 1 else 0
        RING_SCALE -> layout.ringScalePercent
        RING_STROKE -> layout.ringStrokeWidth
        PERCENT_X -> layout.percentOffsetXPercent
        PERCENT_Y -> layout.percentOffsetYPercent
        PERCENT_TEXT_SIZE -> layout.percentTextSize
        PERCENT_TEXT_WEIGHT -> layout.percentTextWeight
        LOW_THRESHOLD -> layout.lowThresholdPercent
    }

    fun write(layout: BatteryRingLayout, value: Int): BatteryRingLayout = when (this) {
        RING_TRACK -> layout.copy(ringTrackVisible = value != 0)
        CHARGE_SWEEP -> layout.copy(chargeSweepEnabled = value != 0)
        PERCENT_SHADOW -> layout.copy(percentShadowEnabled = value != 0)
        RING_SCALE -> layout.copy(ringScalePercent = value)
        RING_STROKE -> layout.copy(ringStrokeWidth = value)
        PERCENT_X -> layout.copy(percentOffsetXPercent = value)
        PERCENT_Y -> layout.copy(percentOffsetYPercent = value)
        PERCENT_TEXT_SIZE -> layout.copy(percentTextSize = value)
        PERCENT_TEXT_WEIGHT ->
            layout.copy(percentTextWeight = BatteryRingLayout.quantizeWeight(value))
        LOW_THRESHOLD -> layout.copy(lowThresholdPercent = value)
    }

    /**
     * 这一项的滑杆区间。水平偏移随「数字电量」位置预设变（左侧 -20 ~ 0、右侧 0 ~ +20），
     * 所以要把当前位置传进来；其余项都是固定区间。
     */
    fun rangeFor(position: BatteryPercentPosition): IntRange = when (this) {
        PERCENT_X -> BatteryRingLayout.percentOffsetXRange(position)
        PERCENT_Y -> BatteryRingLayout.PERCENT_OFFSET_MIN..BatteryRingLayout.PERCENT_OFFSET_MAX
        RING_SCALE -> BatteryRingLayout.RING_SCALE_MIN..BatteryRingLayout.RING_SCALE_MAX
        RING_STROKE -> BatteryRingLayout.RING_STROKE_MIN..BatteryRingLayout.RING_STROKE_MAX
        PERCENT_TEXT_SIZE ->
            BatteryRingLayout.PERCENT_TEXT_SIZE_MIN..BatteryRingLayout.PERCENT_TEXT_SIZE_MAX
        PERCENT_TEXT_WEIGHT ->
            BatteryRingLayout.PERCENT_TEXT_WEIGHT_MIN..BatteryRingLayout.PERCENT_TEXT_WEIGHT_MAX
        LOW_THRESHOLD -> BatteryRingLayout.LOW_THRESHOLD_MIN..BatteryRingLayout.LOW_THRESHOLD_MAX
        // 开关不用区间，给个占位值让调用方不必特判。
        RING_TRACK, CHARGE_SWEEP, PERCENT_SHADOW -> 0..1
    }

    /**
     * 这一项的默认值。水平偏移的默认值随位置预设变（右侧 +10%、左侧 -10%），
     * 所以要把当前位置传进来，不能写成一份静态常量。
     */
    fun defaultValueFor(position: BatteryPercentPosition): Int =
        read(BatteryRingLayout.defaultFor(position))

    /** 偏移量带符号显示（+0 没有意义，按 0 处理）；线条大小带百分号；粗细、字号与字重是纯数值。 */
    fun format(value: Int): String = when (unit) {
        BatteryLayoutUnit.OFFSET -> if (value > 0) "+$value%" else "$value%"
        BatteryLayoutUnit.PERCENT -> "$value%"
        BatteryLayoutUnit.PIXEL -> value.toString()
        BatteryLayoutUnit.WEIGHT -> value.toString()
    }
}

/** 「电量环」折叠里的可调项：底轨开关、充电流光开关、线条大小、线条粗细。 */
private val BatteryRingFields = listOf(
    BatteryLayoutField.RING_TRACK,
    BatteryLayoutField.CHARGE_SWEEP,
    BatteryLayoutField.RING_SCALE,
    BatteryLayoutField.RING_STROKE
)

/** 「颜色自定义」里的可调项：低电量阈值。 */
private val BatteryColorFields = listOf(BatteryLayoutField.LOW_THRESHOLD)

/** 「数字电量」折叠里的可调项：数字相对摄像头模组的偏移、字号、字重与投影开关。 */
private val BatteryPercentFields = listOf(
    BatteryLayoutField.PERCENT_X,
    BatteryLayoutField.PERCENT_Y,
    BatteryLayoutField.PERCENT_TEXT_SIZE,
    BatteryLayoutField.PERCENT_TEXT_WEIGHT,
    BatteryLayoutField.PERCENT_SHADOW
)

/**
 * 一组可调项（滑杆 / 开关）+ 一键恢复默认。
 *
 * 「恢复默认」只还原传进来的这一组：线条那一组归「电量环」、数字那组归「数字电量」，
 * 在哪个折叠里点就还原哪个折叠，不会连带动另一组。
 */
@Composable
private fun BatteryLayoutFieldGroup(
    fields: List<BatteryLayoutField>,
    draft: BatteryRingLayout,
    percentPosition: BatteryPercentPosition,
    onDraftChange: (BatteryRingLayout) -> Unit,
    onCommit: (BatteryRingLayout) -> Unit,
    onEditRequest: (BatteryLayoutField) -> Unit,
    /** 不在 [fields] 里的字段（例如线条样式），也要跟着「恢复默认」一起还原。 */
    resetExtra: (BatteryRingLayout) -> BatteryRingLayout = { it },
    /** 只有一项时不单独放「恢复默认」：点标题行的精确输入弹窗里就有。 */
    showReset: Boolean = true,
) {
    fields.forEach { field ->
        if (field.toggle) {
            SwitchPreference(
                checked = field.read(draft) != 0,
                onCheckedChange = { checked ->
                    // 开关没有拖动过程，不用草稿：一次算好就直接落盘。
                    val next = field.write(draft, if (checked) 1 else 0)
                    onDraftChange(next)
                    onCommit(next)
                },
                title = stringResource(field.titleRes)
            )
            return@forEach
        }
        val range = field.rangeFor(percentPosition)
        // 位置一换，旧值可能落在新区间外（右侧 +10 切到左侧），显示与滑杆都先夹一下。
        val value = field.read(draft).coerceIn(range.first, range.last)
        SliderPreference(
            value = value.toFloat(),
            onValueChange = { onDraftChange(field.write(draft, it.roundToInt())) },
            title = stringResource(field.titleRes),
            valueText = field.format(value),
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { onCommit(draft) },
            // 点标题行弹出精确输入：滑杆用来快速试，弹窗用来把数值定死。
            onClick = { onEditRequest(field) }
        )
    }
    if (!showReset) return
    val feedback = LocalUiFeedback.current
    val resetDone = stringResource(R.string.battery_layout_reset_done)
    val undoLabel = stringResource(R.string.common_undo)
    TextButton(
        text = stringResource(R.string.battery_layout_reset),
        onClick = {
            // 逐项恢复成当前位置下的默认值（水平偏移的默认值跟着位置预设走）。
            val before = draft
            val reset = fields.fold(resetExtra(draft)) { acc, field ->
                field.write(acc, field.defaultValueFor(percentPosition))
            }
            onDraftChange(reset)
            onCommit(reset)
            // 整行按钮容易误点：给一次撤销机会，回到点击前的那组值。
            feedback?.show(resetDone, undoLabel) {
                onDraftChange(before)
                onCommit(before)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
    )
}

/** 精确输入弹窗的内容：一个输入框 + 恢复默认 / 取消 / 确认。 */
@Composable
private fun BatteryLayoutInputDialog(
    field: BatteryLayoutField,
    initialValue: Int,
    defaultValue: Int,
    range: IntRange,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit
) {
    // 换一项就换一份草稿，避免上一项的数字串到下一项。
    var text by remember(field) { mutableStateOf(initialValue.toString()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextField(
            text,
            { raw -> text = filterLayoutInput(raw) },
            Modifier.fillMaxWidth()
        )
        // 「恢复默认」是次要操作，降成输入框下方的文字链接，和取消 / 确认分开。
        Text(
            text = stringResource(R.string.battery_layout_reset_to, field.format(defaultValue)),
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(role = Role.Button) { onApply(defaultValue) }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary
        )
        DialogButtonRow(
            modifier = Modifier.padding(top = 8.dp),
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = onCancel,
            primaryText = stringResource(R.string.common_confirm),
            onPrimary = {
                val parsed = text.toIntOrNull() ?: initialValue
                onApply(parsed.coerceIn(range.first, range.last))
            }
        )
    }
}

/** 输入框只收数字与负号，位数按最长取值（+115%）压到 3 位，避免出现解析不了的内容。 */
private fun filterLayoutInput(raw: String): String {
    val negative = raw.startsWith('-')
    val digits = raw.filter { it.isDigit() }.take(3)
    return if (negative) "-$digits" else digits
}

/** 自定义值优先；为空或非法时读 SystemUI 电池资源色，读不到再回落默认值。 */
@Composable
private fun resolveSlotColor(slot: BatteryColorSlot, raw: String): Color {
    val context = LocalContext.current
    val systemColor = remember(slot) {
        runCatching {
            val resources = context.packageManager
                .getResourcesForApplication(SYSTEM_UI_PACKAGE)
            val id = resources.getIdentifier(slot.systemColorRole, "color", SYSTEM_UI_PACKAGE)
            if (id == 0) slot.fallbackColor else resources.getColor(id, null)
        }.getOrDefault(slot.fallbackColor)
    }
    return Color(parseBatteryColor(raw) ?: systemColor)
}

/** 数字自己的覆盖色优先；为空或非法时跟随电量环当前颜色（与背屏绘制端同一判据）。 */
private fun resolvePercentColor(raw: String, ringColor: Color): Color =
    parseBatteryColor(raw)?.let(::Color) ?: ringColor

/**
 * 「电量数字」的颜色区：「跟随电量环」开关在最上，关掉后才展开色盘。
 *
 * 默认跟随，老用户升级后观感不变；关掉时先换成白色 —— 用户反馈里最常见的问题就是
 * 绿 / 红数字被壁纸吃掉，白色是开箱即用的高对比选择，再想换色直接拖色盘。
 */
@Composable
private fun ColumnScope.BatteryPercentColorSection(
    slot: BatteryColorSlot,
    raw: String,
    color: Color,
    percentShown: Boolean,
    onColorChange: (String) -> Unit
) {
    val follow = parseBatteryColor(raw) == null
    if (!percentShown) {
        Text(
            text = stringResource(R.string.battery_percent_color_hidden_hint),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
    SwitchPreference(
        checked = follow,
        onCheckedChange = { checked ->
            onColorChange(if (checked) "" else BatteryPercentContrastColor)
        },
        title = stringResource(R.string.battery_percent_color_follow_ring_title),
        summary = stringResource(R.string.battery_percent_color_follow_ring_summary)
    )
    AnimatedVisibility(visible = !follow) {
        Column {
            BatteryColorEditor(
                editKey = BatteryColorTarget.PERCENT to slot,
                color = color,
                resetLabel = stringResource(R.string.battery_percent_color_reset_white),
                resetValue = BatteryPercentContrastColor,
                resetArgb = parseBatteryColor(BatteryPercentContrastColor) ?: 0xFFFFFFFF.toInt(),
                onColorChange = onColorChange
            )
        }
    }
}

/** 按真实背屏几何绘制预览：面板、摄像头模组、镜头、电量进度线。 */
@Composable
private fun BatteryRearScreenPreview(
    geometry: BatteryRingGeometry.Profile,
    percent: Int,
    charging: Boolean,
    color: Color,
    /** 百分比数字的颜色；跟随电量环时就是 [color]。 */
    percentColor: Color,
    percentPosition: BatteryPercentPosition,
    layout: BatteryRingLayout,
    wallpaper: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    // 预览专属偏移：镜头与电量线整体右移，避免线条左侧贴到面板边框。
    val ringPaths = remember(geometry, layout.ringStyle, layout.ringScalePercent) {
        geometry.ringPaths(layout.ringStyle, layout.ringScalePercent).map { path ->
            path.asComposePath().also { it.translate(Offset(PreviewContentOffsetX, 0f)) }
        }
    }
    val ringMeasures = remember(ringPaths) {
        ringPaths.map { PathMeasure().apply { setPath(it, true) } }
    }
    val ringLengths = remember(ringMeasures) { ringMeasures.map { it.length } }
    val progress = remember { Path() }
    val sweep = remember { Path() }
    // 只在预览「充电中」时跑流光动画，平时不占绘制。
    val sweepPhase = if (charging && layout.chargeSweepEnabled) {
        rememberInfiniteTransition(label = "chargeSweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(CHARGE_SWEEP_DURATION_MS.toInt(), easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = "chargeSweepPhase"
        )
    } else {
        null
    }
    val context = LocalContext.current
    val lensMain = remember(geometry) { decodeDrawable(context, mainLensRes(geometry)) }
    val lensSecondary = remember(geometry) { decodeDrawable(context, secondaryLensRes(geometry)) }
    // 字号与字重随偏好变，每次绘制前设一遍；其余属性固定，建一次就够。
    val percentPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textAlign = AndroidPaint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    // 字重变化时才重建 Typeface，拖动滑杆不会每帧新建。
    val percentTypeface = remember(layout.percentTextWeight) {
        Typeface.create(Typeface.DEFAULT, layout.percentTextWeight, false)
    }
    val panelColor = MiuixTheme.colorScheme.surfaceContainerHighest
    val outlineColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    // 面板圆角轮廓：壁纸底图按它裁剪，和纯色回退共用同一形状。
    val panelCornerRadius = remember(geometry) {
        CornerRadius(geometry.panelCornerRadius, geometry.panelCornerRadius)
    }
    val panelShape = remember(geometry) {
        Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = geometry.referenceWidth,
                    bottom = geometry.referenceHeight,
                    cornerRadius = panelCornerRadius
                )
            )
        }
    }

    Canvas(
        modifier = modifier.aspectRatio(
            geometry.referenceWidth / geometry.referenceHeight
        )
    ) {
        val scaleX = size.width / geometry.referenceWidth
        val scaleY = size.height / geometry.referenceHeight
        withTransform({ scale(scaleX, scaleY, pivot = Offset.Zero) }) {
            val panelSize = Size(
                geometry.referenceWidth,
                geometry.referenceHeight
            )
            // 有当前壁纸就铺真实画面，缺图时回退到纯色面板。
            val background = wallpaper
            if (background != null) {
                clipPath(panelShape) {
                    drawImage(
                        image = background,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(
                            geometry.referenceWidth.toInt(),
                            geometry.referenceHeight.toInt()
                        ),
                        filterQuality = FilterQuality.Medium
                    )
                }
            } else {
                drawRoundRect(
                    color = panelColor,
                    size = panelSize,
                    cornerRadius = panelCornerRadius
                )
            }
            drawRoundRect(
                color = outlineColor,
                size = panelSize,
                cornerRadius = panelCornerRadius,
                style = Stroke(width = 3f)
            )
            val lensSize = IntSize(geometry.previewLensSize, geometry.previewLensSize)
            val lensX = geometry.previewLensX + PreviewContentOffsetX.toInt()
            lensMain?.let {
                drawImage(
                    image = it,
                    dstOffset = IntOffset(lensX, geometry.previewTopLensY),
                    dstSize = lensSize,
                    filterQuality = FilterQuality.Medium
                )
            }
            lensSecondary?.let {
                drawImage(
                    image = it,
                    dstOffset = IntOffset(lensX, geometry.previewBottomLensY),
                    dstSize = lensSize,
                    filterQuality = FilterQuality.Medium
                )
            }
            val strokeWidth = layout.ringStrokeWidth.toFloat()
            // 底轨铺满整圈，先画；已充部分随后盖在上面。0% 时也画，否则电量见底整圈会消失。
            if (layout.ringTrackVisible) {
                ringPaths.forEach { path ->
                    drawPath(
                        path = path,
                        color = Color(trackColorOf(color.toArgb())),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
            progress.reset()
            // 与背屏同一套分配：双环时下环绕满才从上环起笔。
            val stops = ringProgressStops(percent.toFloat(), ringLengths)
            ringMeasures.forEachIndexed { index, measure ->
                val stop = stops[index]
                if (stop > 0f) measure.getSegment(0f, stop, progress, true)
            }
            drawPath(
                path = progress,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            // 充电时与背屏同一套流光：一小段提亮的线沿已充部分跑过去。
            if (sweepPhase != null) {
                sweep.reset()
                chargeSweepSegments(sweepPhase.value, stops).forEach { (index, from, to) ->
                    ringMeasures[index].getSegment(from, to, sweep, true)
                }
                drawPath(
                    path = sweep,
                    color = Color(chargeSweepColor(color.toArgb())),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            geometry.percentTextAnchor(
                percentPosition,
                layout.percentOffsetXPercent,
                layout.percentOffsetYPercent
            )?.let { anchor ->
                percentPaint.color = percentColor.toArgb()
                percentPaint.textSize = layout.percentTextSize.toFloat()
                percentPaint.typeface = percentTypeface
                // 投影开关随偏好变，每次绘制按草稿值设一遍（开关拨动时预览即时跟着变）。
                if (layout.percentShadowEnabled) applyPercentShadow(percentPaint)
                else percentPaint.clearShadowLayer()
                val metrics = percentPaint.fontMetrics
                val baseline = anchor.y - (metrics.ascent + metrics.descent) / 2f
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        "$percent%",
                        anchor.x + PreviewContentOffsetX,
                        baseline,
                        percentPaint
                    )
                }
            }
        }
    }
}

/**
 * 颜色编辑区：色盘、HEX 输入与一个「复位」按钮，内容直接排进外层卡片容器。
 *
 * 环和数字共用：环的复位是「恢复系统默认」（存空串），数字的复位是「高对比白色」。
 * [editKey] 换了（切部分或切状态）就重置 HEX 草稿，避免上一格的输入串到下一格。
 */
@Composable
private fun ColumnScope.BatteryColorEditor(
    editKey: Any,
    color: Color,
    resetLabel: String,
    /** 点复位时写入偏好的值。 */
    resetValue: String,
    /** 点复位后 HEX 框回显的颜色。 */
    resetArgb: Int,
    onColorChange: (String) -> Unit
) {
    val initial = color
    var hexText by remember(editKey) { mutableStateOf(formatBatteryColor(initial.toArgb())) }

    fun fillInputs(argb: Int) {
        hexText = formatBatteryColor(argb)
    }

    fun apply(argb: Int) {
        fillInputs(argb)
        onColorChange(formatBatteryColor(argb))
    }

    Text(
        text = stringResource(R.string.battery_color_rgba_label) + ": " +
            channelOf(initial, 16) + ", " + channelOf(initial, 8) + ", " +
            channelOf(initial, 0) + ", " + "%.1f".format(initial.alpha),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        style = MiuixTheme.textStyles.body1,
        color = MiuixTheme.colorScheme.onSurfaceContainer
    )
    // ColorPicker 默认在顶部画一条当前颜色预览条，与上方背屏预览重复，用 showPreview 关掉。
    ColorPicker(
        color = initial,
        onColorChanged = { apply(it.toArgb()) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        showPreview = false
    )
    Text(
        text = stringResource(R.string.battery_color_hex_label),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
    // 输入框与按钮各占一半宽度，并取同一高度（TextField 无高度参数，按行内最大固有高度对齐）。
    // 非法输入不会落盘（保持上一次的有效颜色），但要让用户看到为什么颜色没跟着变。
    val hexInvalid = hexText.isNotBlank() && parseBatteryColor(hexText) == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = if (hexInvalid) 6.dp else 16.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                parseBatteryColor(text)?.let { apply(it) }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = if (hexInvalid) {
                TextFieldDefaults.textFieldColors(borderColor = MiuixTheme.colorScheme.error)
            } else {
                TextFieldDefaults.textFieldColors()
            }
        )
        TextButton(
            text = resetLabel,
            onClick = {
                fillInputs(resetArgb)
                onColorChange(resetValue)
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
    if (hexInvalid) {
        Text(
            text = stringResource(R.string.battery_color_invalid_value),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.error
        )
    }
}

private fun channelOf(color: Int, shift: Int): Int = (color shr shift and 0xFF)

private fun channelOf(color: Color, shift: Int): Int = channelOf(color.toArgb(), shift)

@DrawableRes
private fun mainLensRes(geometry: BatteryRingGeometry.Profile): Int =
    if (geometry.device == BatteryRingGeometry.pandora.device) {
        R.drawable.rear_camera_lens_q200_main
    } else {
        R.drawable.rear_camera_lens_main
    }

@DrawableRes
private fun secondaryLensRes(geometry: BatteryRingGeometry.Profile): Int =
    if (geometry.device == BatteryRingGeometry.pandora.device) {
        R.drawable.rear_camera_lens_q200_secondary
    } else {
        R.drawable.rear_camera_lens_secondary
    }

private fun decodeDrawable(context: Context, @DrawableRes resId: Int): ImageBitmap? =
    runCatching {
        BitmapFactory.decodeResource(context.resources, resId)?.asImageBitmap()
    }.getOrNull()
