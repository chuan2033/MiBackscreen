package hook.HyperBackscreen.ui.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private data class InstalledAppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
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
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
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

    DetailPageScaffold(
        title = stringResource(R.string.app_picker_title),
        onBack = onBack,
    ) {
        // 「名单非空即启用、清空即关闭」是隐式规则，在入口页把它说清楚。
        item(key = "picker_hint") {
            Text(
                text = stringResource(R.string.app_picker_double_tap_hint),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        item {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
                label = stringResource(R.string.app_picker_search_hint),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            TabRowWithContour(
                tabs = AppFilter.entries.map { entry ->
                    if (entry == AppFilter.SELECTED && selectedSet.isNotEmpty()) {
                        stringResource(entry.labelRes) + " " + selectedSet.size
                    } else {
                        stringResource(entry.labelRes)
                    }
                },
                selectedTabIndex = filter.ordinal,
                onTabSelected = { index -> AppFilter.entries.getOrNull(index)?.let { filter = it } }
            )
            Spacer(Modifier.height(12.dp))
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
                item { MessageCard(text = stringResource(R.string.app_picker_empty)) }
            }
            items(filteredApps, key = { it.packageName }) { app ->
                CardBlock(pressFeedbackType = PressFeedbackType.None) {
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
        val drawable = app.icon
        if (drawable != null) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { view ->
                    val icon = drawable.constantState?.newDrawable()?.mutate() ?: drawable
                    view.setImageDrawable(icon)
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
            val icon = runCatching { applicationInfo.loadIcon(packageManager) }.getOrNull()
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            InstalledAppItem(label, packageName, icon, isSystem = applicationInfo.flags and systemFlags != 0)
        }
        .distinctBy { it.packageName }
        .toList()
    val collator = Collator.getInstance(Locale.getDefault())
    return apps.sortedWith { left, right ->
        val labelCompare = collator.compare(left.label, right.label)
        if (labelCompare != 0) labelCompare else left.packageName.compareTo(right.packageName)
    }
}
