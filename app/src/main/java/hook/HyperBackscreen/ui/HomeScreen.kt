package hook.HyperBackscreen.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.util.AppLanguage
import hook.HyperBackscreen.ui.util.ThemeMode
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.about.AboutPage
import hook.HyperBackscreen.ui.about.DonatePage
import hook.HyperBackscreen.ui.about.LicensePage
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.FloatingBottomBar
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import top.yukonga.miuix.kmp.basic.SnackbarHost
import hook.HyperBackscreen.ui.components.ScrollGlassIconButton
import hook.HyperBackscreen.ui.animation.crossAxisPagerGestures
import hook.HyperBackscreen.ui.animation.CrossAxisPagerNestedScrollConnection
import hook.HyperBackscreen.ui.animation.springToPage
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import hook.HyperBackscreen.ui.config.AppPickerPage
import hook.HyperBackscreen.ui.config.ConfigPage
import hook.HyperBackscreen.ui.function.FunctionPage
import hook.HyperBackscreen.ui.home.HomePage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
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
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

private data class NavigationTabItem(
    val tab: HomeNavigationPolicy.Tab,
    val labelRes: Int,
    val icon: ImageVector
)

private val navItems = HomeNavigationPolicy.mainTabs().map { tab ->
    when (tab) {
        HomeNavigationPolicy.Tab.HOME -> NavigationTabItem(tab, R.string.nav_home, MiuixIcons.Home)
        HomeNavigationPolicy.Tab.FUNCTION -> NavigationTabItem(tab, R.string.nav_function, MiuixIcons.Tune)
        HomeNavigationPolicy.Tab.ABOUT -> NavigationTabItem(tab, R.string.nav_about, MiuixIcons.Info)
    }
}

private class TabPageRequest(val index: Int)

private data class RestartScopeItem(
    val labelRes: Int,
    val packageName: String
)

private val restartScopeItems = listOf(
    RestartScopeItem(R.string.restart_system, Constants.SYSTEM_PACKAGE),
    RestartScopeItem(R.string.restart_voice_assist, Constants.VOICE_ASSIST_PACKAGE),
    RestartScopeItem(R.string.restart_backscreen, Constants.TARGET_PACKAGE),
    RestartScopeItem(R.string.restart_theme_manager, Constants.THEME_STORE_PACKAGE)
)

@Composable
internal fun HomeScreen(
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    bottomBarBlur: Boolean,
    appLanguage: AppLanguage,
    checkUpdates: Boolean,
    enableAppCard: Boolean,
    disableRearScreenCover: Boolean,
    disableDoubleTapWake: Boolean,
    doubleTapWakeDisabledPackages: String,
    launcherIconHidden: Boolean,
    moduleActivated: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onDisableDoubleTapWakeChange: (Boolean) -> Unit,
    onDoubleTapWakeDisabledPackagesChange: (String) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onForceStopPackage: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    enablePickup: Boolean,
    onEnablePickupChange: (Boolean) -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(HomeNavigationPolicy.Tab.HOME) }
    val backStack = rememberNavBackStack<HomeRoute>(HomeRoute.Main)
    val homeListState = rememberLazyListState()
    val functionListState = rememberLazyListState()
    val aboutListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Covered entries can remain composed in miuix-nav 0.9.4; release their input focus.
    LaunchedEffect(backStack.lastOrNull()) {
        focusManager.clearFocus()
    }
    val navigateBack: () -> Unit = {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
    fun openDetail(route: HomeRoute) {
        // Repeated taps must not push duplicate content keys during a transition.
        if (backStack.lastOrNull() == HomeRoute.Main) backStack.add(route)
    }

    val feedback = LocalUiFeedback.current
    Box(Modifier.fillMaxSize()) {
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = navigateBack,
        effects = NavDisplayEffects(
            blockInputDuringTransition = true,
            backdropColor = MiuixTheme.colorScheme.surface
        )
    ) {
        entry<HomeRoute> { page ->
            // A covered entry stays alive for back navigation, but must not expose hidden controls.
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (page != backStack.lastOrNull()) Modifier.clearAndSetSemantics {} else Modifier
                )
            ) {
                when (page) {
                    HomeRoute.Settings -> SettingsPage(
                        floatingNavBar = floatingNavBar,
                        liquidGlass = liquidGlass,
                        bottomBarBlur = bottomBarBlur,
                        appLanguage = appLanguage,
                        checkUpdates = checkUpdates,
                        enableAppCard = enableAppCard,
                        launcherIconHidden = launcherIconHidden,
                        onFloatingNavBarChange = onFloatingNavBarChange,
                        onLiquidGlassChange = onLiquidGlassChange,
                        onBottomBarBlurChange = onBottomBarBlurChange,
                        onAppLanguageChange = onAppLanguageChange,
                        onCheckUpdatesChange = onCheckUpdatesChange,
                        onEnableAppCardChange = onEnableAppCardChange,
                        onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                        onBack = navigateBack
                    )
                    HomeRoute.License -> LicensePage(onBack = navigateBack)
                    HomeRoute.Donate -> DonatePage(onBack = navigateBack)
                    HomeRoute.AppPicker -> AppPickerPage(
                        disableDoubleTapWake = disableDoubleTapWake,
                        onDisableDoubleTapWakeChange = onDisableDoubleTapWakeChange,
                        selectedPackages = doubleTapWakeDisabledPackages,
                        onSelectedPackagesChange = onDoubleTapWakeDisabledPackagesChange,
                        onBack = navigateBack
                    )
                    HomeRoute.Main -> MainContent(
                        selected = selected,
                        homeListState = homeListState,
                        functionListState = functionListState,
                        aboutListState = aboutListState,
                        onSelectedChange = { selected = it },
                        disableLongPress = disableLongPress,
                        removeWallpaperLimit = removeWallpaperLimit,
                        removeAppCardLimit = removeAppCardLimit,
                        fixRearScreenApply = fixRearScreenApply,
                        floatingNavBar = floatingNavBar,
                        liquidGlass = liquidGlass,
                        bottomBarBlur = bottomBarBlur,
                        appLanguage = appLanguage,
                        checkUpdates = checkUpdates,
                        disableRearScreenCover = disableRearScreenCover,
                        doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                        launcherIconHidden = launcherIconHidden,
                        moduleActivated = moduleActivated,
                        onDisableLongPressChange = onDisableLongPressChange,
                        onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                        onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                        onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                        onFloatingNavBarChange = onFloatingNavBarChange,
                        onLiquidGlassChange = onLiquidGlassChange,
                        onBottomBarBlurChange = onBottomBarBlurChange,
                        onAppLanguageChange = onAppLanguageChange,
                        onCheckUpdatesChange = onCheckUpdatesChange,
                        onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                        onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                        onSettingsClick = { openDetail(HomeRoute.Settings) },
                        onAddDisabledAppsClick = { openDetail(HomeRoute.AppPicker) },
                        onLicenseClick = { openDetail(HomeRoute.License) },
                        onDonateClick = { openDetail(HomeRoute.Donate) },
                        onForceStopPackage = onForceStopPackage,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                        enablePickup = enablePickup,
                        onEnablePickupChange = onEnablePickupChange
                    )
                }
            }
        }
    }
        if (feedback != null) {
            SnackbarHost(
                state = feedback.host,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().imePadding()
                    .padding(bottom = if (backStack.lastOrNull() == HomeRoute.Main) 100.dp else 12.dp)
            )
        }
    }

}

@Composable
private fun SettingsPage(
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    bottomBarBlur: Boolean,
    appLanguage: AppLanguage,
    checkUpdates: Boolean,
    enableAppCard: Boolean,
    launcherIconHidden: Boolean,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.settings_title),
                    largeTitle = stringResource(R.string.settings_title),
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
                    ConfigPage(
                        floatingNavBar = floatingNavBar,
                        liquidGlass = liquidGlass,
                        bottomBarBlur = bottomBarBlur,
                        appLanguage = appLanguage,
                        checkUpdates = checkUpdates,
                        enableAppCard = enableAppCard,
                        launcherIconHidden = launcherIconHidden,
                        onFloatingNavBarChange = onFloatingNavBarChange,
                        onLiquidGlassChange = onLiquidGlassChange,
                        onBottomBarBlurChange = onBottomBarBlurChange,
                        onAppLanguageChange = onAppLanguageChange,
                        onCheckUpdatesChange = onCheckUpdatesChange,
                        onEnableAppCardChange = onEnableAppCardChange,
                        onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange
                    )
                }
                item {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }
}

@Composable
private fun MainTabPage(
    tab: HomeNavigationPolicy.Tab,
    listState: LazyListState,
    moduleActivated: Boolean,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    enablePickup: Boolean,
    themeMode: ThemeMode,
    onDonateClick: () -> Unit,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onEnablePickupChange: (Boolean) -> Unit,
    onAddDisabledAppsClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onRestartClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    // Each retained pager page must own its capture layer and coordinates. Sharing one
    // lets off-screen pages overwrite the current page's backdrop during navigation.
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val actionCovered by remember(listState, scrollBehavior, density) {
        derivedStateOf {
            // Short pages can collapse the large title without moving the list itself.
            val threshold = with(density) { 8.dp.toPx() }
            scrollBehavior.state.heightOffset < -threshold ||
                listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > threshold
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(navItems.first { it.tab == tab }.labelRes),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        val topAction = HomeNavigationPolicy.topActionFor(tab)
                        ScrollGlassIconButton(
                            image = when (topAction) {
                                HomeNavigationPolicy.TopAction.RESTART -> MiuixIcons.Refresh
                                HomeNavigationPolicy.TopAction.SETTINGS -> MiuixIcons.Settings
                            },
                            description = stringResource(when (topAction) {
                                HomeNavigationPolicy.TopAction.RESTART -> R.string.restart_scope
                                HomeNavigationPolicy.TopAction.SETTINGS -> R.string.settings_title
                            }),
                            backdrop = backdrop,
                            covered = actionCovered,
                            onClick = {
                                when (topAction) {
                                    HomeNavigationPolicy.TopAction.RESTART -> onRestartClick()
                                    HomeNavigationPolicy.TopAction.SETTINGS -> onSettingsClick()
                                }
                            }
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding()
                )
            ) {
                when (tab) {
                    HomeNavigationPolicy.Tab.HOME -> item {
                        HomePage(
                            moduleActivated = moduleActivated,
                            usesDynamicColors = themeMode.usesDynamicColors,
                            onDonateClick = onDonateClick
                        )
                    }
                    HomeNavigationPolicy.Tab.FUNCTION -> item {
                        FunctionPage(
                            disableLongPress = disableLongPress,
                            removeWallpaperLimit = removeWallpaperLimit,
                            removeAppCardLimit = removeAppCardLimit,
                            fixRearScreenApply = fixRearScreenApply,
                            disableRearScreenCover = disableRearScreenCover,
                            doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                            enablePickup = enablePickup,
                            onDisableLongPressChange = onDisableLongPressChange,
                            onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                            onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                            onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                            onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                            onEnablePickupChange = onEnablePickupChange,
                            onAddDisabledAppsClick = onAddDisabledAppsClick
                        )
                    }
                    HomeNavigationPolicy.Tab.ABOUT -> item {
                        AboutPage(onLicenseClick = onLicenseClick)
                    }
                }
                item {
                    Spacer(
                        Modifier
                            .height(HomeNavigationPolicy.mainContentBottomSpacerDp().dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }
}

@Composable
private fun MainContent(
    selected: HomeNavigationPolicy.Tab,
    homeListState: LazyListState,
    functionListState: LazyListState,
    aboutListState: LazyListState,
    onSelectedChange: (HomeNavigationPolicy.Tab) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    bottomBarBlur: Boolean,
    appLanguage: AppLanguage,
    checkUpdates: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    launcherIconHidden: Boolean,
    moduleActivated: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    onAddDisabledAppsClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onDonateClick: () -> Unit,
    onForceStopPackage: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    enablePickup: Boolean,
    onEnablePickupChange: (Boolean) -> Unit
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val bottomBarBackdrop = rememberLayerBackdrop {
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
    val selectedPage = navItems.indexOfFirst { it.tab == selected }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedPage) { navItems.size }
    var pageRequest by remember { mutableStateOf<TabPageRequest?>(null) }
    val onSelectedChangeUpdated by rememberUpdatedState(onSelectedChange)
    // Clicks issue commands; observing pager motion must never issue another command.
    LaunchedEffect(pageRequest) {
        val request = pageRequest ?: return@LaunchedEffect
        try {
            pagerState.springToPage(request.index)
        } finally {
            if (pageRequest === request) pageRequest = null
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            navItems.getOrNull(page)?.tab?.let(onSelectedChangeUpdated)
        }
    }
    // A click highlights its destination immediately; finger swipes follow the displayed page.
    val navigationIndex = pageRequest?.index ?: pagerState.currentPage
    fun selectPage(index: Int) { pageRequest = TabPageRequest(index) }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .crossAxisPagerGestures(pagerState, onIntercepted = { pageRequest = null })
                .then(
                    if (bottomBarBlur) {
                        Modifier.layerBackdrop(bottomBarBackdrop)
                    } else {
                        Modifier
                    }
                ),
            userScrollEnabled = false,
            pageNestedScrollConnection = CrossAxisPagerNestedScrollConnection,
            beyondViewportPageCount = navItems.size - 1,
            overscrollEffect = null,
            flingBehavior = flingBehavior(
                state = pagerState,
                snapAnimationSpec = PagerNavigationSpringSpec
            )
        ) { page ->
            val tab = navItems[page].tab
            MainTabPage(
                tab = tab,
                listState = when (tab) {
                    HomeNavigationPolicy.Tab.HOME -> homeListState
                    HomeNavigationPolicy.Tab.FUNCTION -> functionListState
                    HomeNavigationPolicy.Tab.ABOUT -> aboutListState
                },
                moduleActivated = moduleActivated,
                disableLongPress = disableLongPress,
                removeWallpaperLimit = removeWallpaperLimit,
                removeAppCardLimit = removeAppCardLimit,
                fixRearScreenApply = fixRearScreenApply,
                disableRearScreenCover = disableRearScreenCover,
                doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                enablePickup = enablePickup,
                themeMode = themeMode,
                onDonateClick = onDonateClick,
                onDisableLongPressChange = onDisableLongPressChange,
                onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                onEnablePickupChange = onEnablePickupChange,
                onAddDisabledAppsClick = onAddDisabledAppsClick,
                onLicenseClick = onLicenseClick,
                onRestartClick = { showRestartDialog = true },
                onSettingsClick = onSettingsClick
            )
        }

            if (!floatingNavBar) {
                NavigationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(
                            if (bottomBarBlur) {
                                Modifier.textureBlur(
                                    backdrop = bottomBarBackdrop,
                                    shape = RectangleShape,
                                    blurRadius = 20f,
                                    colors = glassBlurColors
                                )
                            } else {
                                Modifier
                            }
                        ),
                    color = if (bottomBarBlur) {
                        MiuixTheme.colorScheme.surface.copy(alpha = 0.48f)
                    } else {
                        MiuixTheme.colorScheme.surface
                    },
                    showDivider = true
                ) {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            selected = navItems[navigationIndex].tab == item.tab,
                            onClick = { selectPage(navItems.indexOf(item)) },
                            icon = item.icon,
                            label = stringResource(item.labelRes),
                            colors = NavigationBarDefaults.navigationBarItemColors(
                                unselectedContentColor = MiuixTheme.colorScheme.onSurface,
                                selectedContentColor = MiuixTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            if (floatingNavBar && liquidGlass) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                        .offset(y = 12.dp)
                ) {
                    FloatingBottomBar(
                        items = navItems.map { NavigationItem(stringResource(it.labelRes), it.icon) },
                        selectedIndex = navigationIndex,
                        onItemClick = ::selectPage,
                        backdrop = bottomBarBackdrop,
                        isBlurActive = bottomBarBlur
                    )
                }
            }

            // 悬浮但不开液态玻璃时走 miuix 原生浮动导航栏，只显示图标。
            if (floatingNavBar && !liquidGlass) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .offset(y = 40.dp)
                ) {
                    FloatingNavigationBar(
                        modifier = Modifier.then(
                            if (bottomBarBlur) {
                                Modifier.textureBlur(
                                    backdrop = bottomBarBackdrop,
                                    shape = RoundedCornerShape(28.dp),
                                    blurRadius = 20f,
                                    colors = glassBlurColors
                                )
                            } else {
                                Modifier
                            }
                        ),
                        color = if (bottomBarBlur) {
                            MiuixTheme.colorScheme.surface.copy(alpha = 0.48f)
                        } else {
                            MiuixTheme.colorScheme.surfaceContainer
                        },
                        cornerRadius = 28.dp,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        showDivider = false
                    ) {
                        navItems.forEach { item ->
                            FloatingNavigationBarItem(
                                selected = navItems[navigationIndex].tab == item.tab,
                                onClick = { selectPage(navItems.indexOf(item)) },
                                icon = item.icon,
                                label = stringResource(item.labelRes),
                                colors = NavigationBarDefaults.navigationBarItemColors(
                                    unselectedContentColor = MiuixTheme.colorScheme.onSurface,
                                    selectedContentColor = MiuixTheme.colorScheme.primary
                                )
                            )
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
