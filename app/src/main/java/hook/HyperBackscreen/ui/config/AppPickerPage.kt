package hook.HyperBackscreen.ui.config

import top.yukonga.miuix.kmp.basic.Icon
import hook.HyperBackscreen.ui.components.TopBarIconButton
import hook.HyperBackscreen.ui.components.TopBarSearchField
import hook.HyperBackscreen.ui.components.onBoundsInOverlay
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.More
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.LocalPageBackdrop
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.AppPickerFilter
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.components.CardBlock
import java.text.Collator
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private data class InstalledAppItem(
    val label: String,
    val packageName: String,
    /** 只带 ApplicationInfo、不预先加载图标：图标在列表行可见时才按需加载（见 [AppIcon]）。 */
    val appInfo: ApplicationInfo?,
    /** 预装 / 系统更新过的系统应用；游戏基本都是用户应用，按这个拆开列表能短很多。 */
    val isSystem: Boolean = false
)

/** 列表筛选：默认只看用户应用；「已选」用来快速复查名单。顺序即分段控件顺序。 */
private enum class AppFilter(@get:StringRes val labelRes: Int) {
    USER(R.string.app_picker_filter_user),
    SYSTEM(R.string.app_picker_filter_system),
    SELECTED(R.string.app_picker_filter_selected),
    ALL(R.string.app_picker_filter_all),
}

@Composable
internal fun AppPickerPage(
    selectedPackages: String,
    onSelectedPackagesChange: (String) -> Unit,
    onBack: () -> Unit,
    // 同一个选择页给多份名单复用：标题与页首说明按名单区分，默认是「双击亮屏黑名单」。
    @StringRes titleRes: Int = R.string.app_picker_title,
    @StringRes hintRes: Int = R.string.app_picker_double_tap_hint,
) {
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<InstalledAppItem>()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val selectedSet = remember(selectedPackages) {
        PackageListCodec.parse(selectedPackages)
    }
    val knownPackages = remember(apps) {
        apps.mapTo(HashSet()) { it.packageName }
    }
    val mergedApps = remember(apps, selectedSet, knownPackages) {
        val missing = selectedSet
            .filterNot { knownPackages.contains(it) }
            .map { InstalledAppItem(it, it, null) }
        (missing + apps).distinctBy { it.packageName }
    }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER) }
    // 已选应用置顶，但只按「列表加载完那一刻」的名单排：勾选 / 取消时不重排，
    // 否则手指下的条目会突然跳走。
    val pinnedPackages = remember(apps) { selectedSet }
    val filteredApps = remember(mergedApps, query, filter, selectedSet, pinnedPackages) {
        mergedApps
            .filter { app ->
                when (filter) {
                    AppFilter.USER -> !app.isSystem
                    AppFilter.SYSTEM -> app.isSystem
                    AppFilter.SELECTED -> app.packageName in selectedSet
                    AppFilter.ALL -> true
                }
            }
            .filter { AppPickerFilter.matches(it.label, it.packageName, query) }
            .sortedBy { it.packageName !in pinnedPackages }
    }

    LaunchedEffect(context, loadAttempt) {
        loading = true
        loadFailed = false
        when (val result = withContext(Dispatchers.IO) {
            loadAppList { loadInstalledApps(context) }
        }) {
            is AppListLoadResult.Loaded -> apps = result.apps
            is AppListLoadResult.Failed -> {
                loadFailed = true
                Log.w(Constants.LOG_TAG, "Unable to load visible applications", result.error)
            }
        }
        loading = false
    }

    var showFilterMenu by remember { mutableStateOf(false) }
    var filterAnchorBounds by remember { mutableStateOf(Rect.Zero) }
    val filterLabels = AppFilter.entries.map { entry ->
        if (entry == AppFilter.SELECTED && selectedSet.isNotEmpty()) {
            stringResource(entry.labelRes) + " (" + selectedSet.size + ")"
        } else {
            stringResource(entry.labelRes)
        }
    }

    DetailPageScaffold(
        title = stringResource(titleRes),
        onBack = onBack,
        // 筛选收进右上角「更多」菜单：列表顶部不再常驻一排分段按钮。
        actions = {
            TopBarIconButton(
                onClick = { showFilterMenu = true },
                modifier = Modifier.onBoundsInOverlay { filterAnchorBounds = it },
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.More,
                    contentDescription = stringResource(R.string.app_picker_filter_menu),
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
        },
        // 搜索框固定在顶栏玻璃里：往下翻很长的应用列表时随时能改关键词。
        topBarBottom = {
            TopBarSearchField(
                query = query,
                onQueryChange = { query = it },
                label = stringResource(R.string.app_picker_search_hint),
            )
        },
        overlay = {
            val filterMenuVisible = showFilterMenu && filterAnchorBounds != Rect.Zero
            // GlassPopup 只淡入淡出菜单文字，玻璃面板本身一直不透明：点开头几帧和收起最后一百多毫秒，
            // 缩成一条扁胶囊的面板会挂在「更多」按钮上方。让整块面板跟文字走同一条淡入淡出曲线。
            val filterMenuAlpha by animateFloatAsState(
                targetValue = if (filterMenuVisible) 1f else 0f,
                animationSpec = GlassMotion.popupMorphFade(filterMenuVisible),
                label = "filterMenuPanelFade",
            )
            GlassPopup(
                show = filterMenuVisible,
                onDismissRequest = { showFilterMenu = false },
                anchorBounds = filterAnchorBounds,
                backdrop = LocalPageBackdrop.current,
                modifier = Modifier.graphicsLayer { alpha = filterMenuAlpha },
            ) {
                filterLabels.forEachIndexed { index, label ->
                    GlassPopupItem(
                        text = label,
                        selected = filter.ordinal == index,
                        onClick = {
                            AppFilter.entries.getOrNull(index)?.let { filter = it }
                            showFilterMenu = false
                        },
                    )
                }
            }
        },
    ) {
        // 「名单非空即启用、清空即关闭」是隐式规则，在入口页把它说清楚。
        item(key = "picker_hint") {
            Text(
                text = stringResource(hintRes),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        item {
            // 筛选在右上角菜单里，这里写明当前看的是哪一类、有多少个。
            if (!loading) {
                Text(
                    text = stringResource(
                        R.string.app_picker_showing,
                        stringResource(filter.labelRes),
                        filteredApps.size
                    ),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        if (loading) {
            item {
                MessageCard(text = stringResource(R.string.app_picker_loading))
            }
        } else {
            if (loadFailed || apps.isEmpty()) {
                item(key = "load_status") {
                    CardBlock(pressFeedbackType = PressFeedbackType.None) {
                        Text(
                            text = stringResource(if (loadFailed) R.string.app_picker_load_failed else R.string.app_picker_unavailable),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextButton(
                            text = stringResource(R.string.common_retry),
                            onClick = { loadAttempt++ },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            } else if (selectedSet.any { it !in knownPackages }) {
                item(key = "visibility_notice") {
                    MessageCard(text = stringResource(R.string.app_picker_partial))
                }
            }
            if (filteredApps.isEmpty() && (query.isNotBlank() || apps.isNotEmpty())) {
                item(key = "empty") { MessageCard(text = stringResource(R.string.app_picker_empty)) }
            }
            items(filteredApps, key = { it.packageName }) { app ->
                // 换筛选时条目淡入淡出、留下的条目平滑挪位，而不是整列瞬间替换。
                CardBlock(
                    modifier = Modifier.animateItem(),
                    pressFeedbackType = PressFeedbackType.None
                ) {
                    CheckboxPreference(
                        title = app.label,
                        summary = app.packageName,
                        checked = selectedSet.contains(app.packageName),
                        onCheckedChange = { checked ->
                            val next = LinkedHashSet(selectedSet)
                            if (checked) {
                                next.add(app.packageName)
                            } else {
                                next.remove(app.packageName)
                            }
                            onSelectedPackagesChange(PackageListCodec.encode(next))
                        },
                        checkboxLocation = CheckboxLocation.End,
                        startAction = {
                            AppIcon(app)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageCard(text: String) {
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun AppIcon(app: InstalledAppItem) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(HomeUiTokens.CornerSmall))
            .background(MiuixTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        // 按可见行懒加载：只有组合到屏幕上的行才去 PackageManager 取图标，并放进有上限的缓存，
        // 不再在进入页面时为全部已安装应用（含系统应用）一次性 loadIcon 并整页常驻内存。
        val context = LocalContext.current
        val drawable by produceState(AppIconCache.get(app.packageName), app.packageName, app.appInfo) {
            if (value != null) return@produceState
            val info = app.appInfo ?: return@produceState
            value = withContext(Dispatchers.IO) {
                AppIconCache.load(context.applicationContext.packageManager, info)
            }
        }
        val icon = drawable
        if (icon != null) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { view ->
                    view.setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = app.label.take(1),
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

private fun loadInstalledApps(context: Context): List<InstalledAppItem> {
    val packageManager = context.packageManager
    val applications = packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    val apps = applications
        .asSequence()
        .filter { it.enabled }
        .filter { it.packageName != context.packageName }
        .map { applicationInfo ->
            val packageName = applicationInfo.packageName
            val label = try {
                applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName }
            } catch (_: RuntimeException) {
                packageName
            }
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            InstalledAppItem(label, packageName, applicationInfo, isSystem = applicationInfo.flags and systemFlags != 0)
        }
        .distinctBy { it.packageName }
        .toList()
    val collator = Collator.getInstance(Locale.getDefault())
    return apps.sortedWith { left, right ->
        val labelCompare = collator.compare(left.label, right.label)
        if (labelCompare != 0) labelCompare else left.packageName.compareTo(right.packageName)
    }
}

/**
 * 应用图标的进程内缓存：按包名、有条目上限（LRU），翻页回来不必重新加载，
 * 也不会把几百个应用的图标全部常驻内存。
 */
private object AppIconCache {
    private const val MAX_ENTRIES = 120
    private val cache = android.util.LruCache<String, Drawable>(MAX_ENTRIES)

    fun get(packageName: String): Drawable? = cache.get(packageName)

    fun load(packageManager: PackageManager, info: ApplicationInfo): Drawable? {
        cache.get(info.packageName)?.let { return it }
        val icon = runCatching { info.loadIcon(packageManager) }.getOrNull() ?: return null
        cache.put(info.packageName, icon)
        return icon
    }
}
