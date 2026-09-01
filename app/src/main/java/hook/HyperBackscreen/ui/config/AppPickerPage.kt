package hook.HyperBackscreen.ui.config

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.AppPickerFilter
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import java.text.Collator
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private data class InstalledAppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?
)

@Composable
internal fun AppPickerPage(
    selectedPackages: String,
    onSelectedPackagesChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyList<InstalledAppItem>()) }
    var loading by remember { mutableStateOf(true) }
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
    val filteredApps = remember(mergedApps, query) {
        mergedApps.filter {
            AppPickerFilter.matches(it.label, it.packageName, query)
        }
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(context) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context)
        }
        loading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.app_picker_title),
                    largeTitle = stringResource(R.string.app_picker_title),
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
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding()
                )
            ) {
                item {
                    Spacer(Modifier.height(12.dp))
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
                }

                when {
                    loading -> item {
                        MessageCard(text = stringResource(R.string.app_picker_loading))
                    }
                    filteredApps.isEmpty() -> item {
                        MessageCard(text = stringResource(R.string.app_picker_empty))
                    }
                    else -> items(filteredApps, key = { it.packageName }) { app ->
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

                item {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
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
            .clip(RoundedCornerShape(10.dp))
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
            val label = applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName }
            val icon = runCatching { applicationInfo.loadIcon(packageManager) }.getOrNull()
            InstalledAppItem(label, packageName, icon)
        }
        .distinctBy { it.packageName }
        .toList()
    val collator = Collator.getInstance(Locale.getDefault())
    return apps.sortedWith { left, right ->
        val labelCompare = collator.compare(left.label, right.label)
        if (labelCompare != 0) labelCompare else left.packageName.compareTo(right.packageName)
    }
}
