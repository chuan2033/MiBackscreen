package hook.HyperBackscreen.ui.battery

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
    idleColor: String,
    chargingColor: String,
    lowColor: String,
    onColorChange: (BatteryColorSlot, String) -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    var selected by remember { mutableStateOf(BatteryColorSlot.IDLE) }
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    // 与绘制端同一判据：几何只在校准机型上成立。
    val rearScreenGeometry = remember { BatteryRingGeometry.forDevice(Build.DEVICE) }
    val rearScreenSupported = rearScreenGeometry != null

    if (rearScreenSupported) {
        // 背屏当前壁纸的真实截图。壁纸随时可能在背屏上被切换或重新截图，这里按文件指纹轮询，
        // 变化时重新解码；读取失败保留上一张，预览在完全没有截图时回退到纯色面板。
        LaunchedEffect(Unit) {
            var lastStamp: String? = null
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

    fun rawFor(slot: BatteryColorSlot): String = when (slot) {
        BatteryColorSlot.IDLE -> idleColor
        BatteryColorSlot.CHARGING -> chargingColor
        BatteryColorSlot.LOW -> lowColor
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.function_battery_color_title),
                    largeTitle = stringResource(R.string.function_battery_color_title),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.layerBackdrop(backdrop)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = paddingValues.calculateTopPadding())
            ) {
                item(key = "top_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
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
                    return@LazyColumn
                }
                item(key = "rear_screen_preview") {
                    BatteryRearScreenPreview(
                        geometry = rearScreenGeometry,
                        slot = selected,
                        color = resolveSlotColor(selected, rawFor(selected)),
                        wallpaper = wallpaper,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
                item(key = "preview_controls_gap") {
                    Spacer(Modifier.height(20.dp))
                }
                item(key = "color_content") {
                    CardBlock(pressFeedbackType = PressFeedbackType.None) {
                        val currentRaw = rawFor(selected)
                        val custom = parseBatteryColor(currentRaw)
                        WindowDropdownPreference(
                            items = BatteryColorSlot.entries.map { stringResource(it.labelRes) },
                            selectedIndex = selected.ordinal,
                            title = stringResource(R.string.battery_color_state_title),
                            summary = formatBatteryColor(
                                custom ?: resolveSlotColor(selected, currentRaw).toArgb()
                            ),
                            onSelectedIndexChange = { index ->
                                BatteryColorSlot.entries.getOrNull(index)?.let { selected = it }
                            }
                        )
                        BatteryColorEditor(
                            slot = selected,
                            raw = rawFor(selected),
                            onColorChange = { value -> onColorChange(selected, value) }
                        )
                    }
                }
            }
        }
    }
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

/** 按真实背屏几何绘制预览：面板、摄像头模组、镜头、电量进度线。 */
@Composable
private fun BatteryRearScreenPreview(
    geometry: BatteryRingGeometry.Profile,
    slot: BatteryColorSlot,
    color: Color,
    wallpaper: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    // 预览专属偏移：镜头与电量线整体右移，避免线条左侧贴到面板边框。
    val contour = remember(geometry) {
        geometry.contourPath().asComposePath().also {
            it.translate(Offset(PreviewContentOffsetX, 0f))
        }
    }
    val measure = remember(contour) { PathMeasure().apply { setPath(contour, true) } }
    val progress = remember { Path() }
    val context = LocalContext.current
    val lensMain = remember(geometry) { decodeDrawable(context, mainLensRes(geometry)) }
    val lensSecondary = remember(geometry) { decodeDrawable(context, secondaryLensRes(geometry)) }
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
            progress.reset()
            measure.getSegment(
                0f,
                measure.length * slot.previewPercent / 100f,
                progress,
                true
            )
            drawPath(
                path = progress,
                color = color,
                style = Stroke(width = BatteryRingGeometry.STROKE_WIDTH, cap = StrokeCap.Round)
            )
        }
    }
}

/** 颜色编辑区：色盘、HEX 输入与恢复系统默认，内容直接排进外层卡片容器。 */
@Composable
private fun ColumnScope.BatteryColorEditor(
    slot: BatteryColorSlot,
    raw: String,
    onColorChange: (String) -> Unit
) {
    val initial = resolveSlotColor(slot, raw)
    val systemArgb = resolveSlotColor(slot, "").toArgb()
    var hexText by remember(slot) { mutableStateOf(formatBatteryColor(initial.toArgb())) }

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextField(
            hexText,
            { text ->
                hexText = text
                parseBatteryColor(text)?.let { apply(it) }
            },
            Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        TextButton(
            text = stringResource(R.string.battery_color_reset_system),
            onClick = {
                fillInputs(systemArgb)
                onColorChange("")
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
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
