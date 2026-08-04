package hook.HyperBackscreen.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.about.AboutPage
import hook.HyperBackscreen.ui.about.LicensePage
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.FloatingBottomBar
import hook.HyperBackscreen.ui.components.FloatingBottomBarItem
import hook.HyperBackscreen.ui.config.ConfigPage
import hook.HyperBackscreen.ui.home.HomePage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

private val navItems = listOf(
    R.string.nav_home to MiuixIcons.Home,
    R.string.nav_config to MiuixIcons.Settings,
    R.string.nav_about to MiuixIcons.Info
)

private data class RestartScopeItem(
    val labelRes: Int,
    val packageName: String
)

private val restartScopeItems = listOf(
    RestartScopeItem(R.string.restart_backscreen, Constants.TARGET_PACKAGE),
    RestartScopeItem(R.string.restart_theme_manager, Constants.THEME_STORE_PACKAGE)
)

@Composable
internal fun HomeScreen(
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    fixRearScreenApply: Boolean,
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onForceStopPackage: (String) -> Unit
) {
    var selected by remember { mutableIntStateOf(0) }
    var showLicense by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = showLicense,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
            }
        },
        label = "LicensePageTransition"
    ) { isLicense ->
        if (isLicense) {
            LicensePage(onBack = { showLicense = false })
        } else {
            MainContent(
                selected = selected,
                onSelectedChange = { selected = it },
                disableLongPress = disableLongPress,
                removeWallpaperLimit = removeWallpaperLimit,
                fixRearScreenApply = fixRearScreenApply,
                floatingNavBar = floatingNavBar,
                liquidGlass = liquidGlass,
                onDisableLongPressChange = onDisableLongPressChange,
                onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                onFloatingNavBarChange = onFloatingNavBarChange,
                onLiquidGlassChange = onLiquidGlassChange,
                onLicenseClick = { showLicense = true },
                onForceStopPackage = onForceStopPackage
            )
        }
    }
}

@Composable
private fun MainContent(
    selected: Int,
    onSelectedChange: (Int) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    fixRearScreenApply: Boolean,
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onLicenseClick: () -> Unit,
    onForceStopPackage: (String) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val glassBlurColors = BlurDefaults.blurColors(
        blendColors = listOf(
            BlendColorEntry(
                color = MiuixTheme.colorScheme.surface.copy(alpha = 0.4f)
            )
        )
    )

    var showRestartDialog by remember { mutableStateOf(false) }
    val checkedItems = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(navItems[selected].first),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { showRestartDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.restart_scope),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.layerBackdrop(backdrop)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding()
                    )
                ) {
                    when (selected) {
                        0 -> item {
                            HomePage(
                                disableLongPress = disableLongPress,
                                removeWallpaperLimit = removeWallpaperLimit,
                                fixRearScreenApply = fixRearScreenApply,
                                onDisableLongPressChange = onDisableLongPressChange,
                                onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                                onFixRearScreenApplyChange = onFixRearScreenApplyChange
                            )
                        }
                        1 -> item {
                            ConfigPage(
                                disableLongPress = disableLongPress,
                                removeWallpaperLimit = removeWallpaperLimit,
                                fixRearScreenApply = fixRearScreenApply,
                                floatingNavBar = floatingNavBar,
                                liquidGlass = liquidGlass,
                                onDisableLongPressChange = onDisableLongPressChange,
                                onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                                onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                                onFloatingNavBarChange = onFloatingNavBarChange,
                                onLiquidGlassChange = onLiquidGlassChange
                            )
                        }
                        2 -> item {
                            AboutPage(onLicenseClick = onLicenseClick)
                        }
                    }
                    item {
                        Spacer(Modifier.height(24.dp).navigationBarsPadding())
                    }
                }
            }

            if (!floatingNavBar) {
                NavigationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .textureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = 20f,
                            colors = glassBlurColors
                        ),
                    color = MiuixTheme.colorScheme.surface.copy(alpha = 0.48f),
                    showDivider = true
                ) {
                    navItems.forEachIndexed { index, (labelRes, icon) ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { onSelectedChange(index) },
                            icon = icon,
                            label = stringResource(labelRes)
                        )
                    }
                }
            }

            if (floatingNavBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    FloatingBottomBar(
                        selectedIndex = { selected },
                        onSelected = { onSelectedChange(it) },
                        backdrop = backdrop,
                        tabsCount = navItems.size,
                        isBlurEnabled = liquidGlass
                    ) {
                        navItems.forEachIndexed { index, (labelRes, icon) ->
                            FloatingBottomBarItem(
                                onClick = { onSelectedChange(index) },
                                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = stringResource(labelRes),
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = stringResource(labelRes),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = showRestartDialog,
        title = stringResource(R.string.restart_title),
        onDismissRequest = { showRestartDialog = false }
    ) {
        restartScopeItems.forEach { item ->
            CheckboxPreference(
                title = stringResource(item.labelRes),
                checked = checkedItems[item.packageName] == true,
                onCheckedChange = { checked ->
                    checkedItems[item.packageName] = checked
                },
                checkboxLocation = CheckboxLocation.End
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.common_select_all),
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = {
                    val allChecked = restartScopeItems.all { checkedItems[it.packageName] == true }
                    restartScopeItems.forEach { checkedItems[it.packageName] = !allChecked }
                }
            )
            Button(
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = {
                    showRestartDialog = false
                    restartScopeItems.forEach { item ->
                        if (checkedItems[item.packageName] == true) {
                            onForceStopPackage(item.packageName)
                        }
                    }
                    checkedItems.clear()
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                content = { Text(stringResource(R.string.common_confirm)) }
            )
        }
    }
}
