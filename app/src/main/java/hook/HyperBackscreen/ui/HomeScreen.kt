package hook.HyperBackscreen.ui

import android.os.Build
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
import hook.HyperBackscreen.ui.battery.BatteryRingGeometry
import hook.HyperBackscreen.ui.battery.BatteryColorPage
import hook.HyperBackscreen.ui.battery.BatteryColorSlot
import hook.HyperBackscreen.ui.battery.BatteryPercentPosition
import hook.HyperBackscreen.ui.battery.BatteryRingLayout
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.FloatingBottomBar
import hook.HyperBackscreen.ui.components.FunctionCountBadge
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import top.yukonga.miuix.kmp.basic.SnackbarHost
import hook.HyperBackscreen.ui.animation.crossAxisPagerGestures
import hook.HyperBackscreen.ui.animation.CrossAxisPagerNestedScrollConnection
import hook.HyperBackscreen.ui.animation.springToPage
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import hook.HyperBackscreen.ui.config.AppPickerPage
import hook.HyperBackscreen.ui.config.ConfigPage
import hook.HyperBackscreen.ui.function.FunctionPage
import hook.HyperBackscreen.ui.function.RearScreenTimingPage
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.pow
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
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

// 顺序与功能页分组保持一致：系统框架、背屏、主题壁纸、超级小爱；智能助理没有对应分组，列在最后。
private val restartScopeItems = listOf(
    RestartScopeItem(R.string.restart_system, Constants.SYSTEM_PACKAGE),
    RestartScopeItem(R.string.restart_backscreen, Constants.TARGET_PACKAGE),
    RestartScopeItem(R.string.restart_theme_manager, Constants.THEME_STORE_PACKAGE),
    RestartScopeItem(R.string.restart_personal_assistant, Constants.PERSONAL_ASSISTANT_PACKAGE)
)

/**
 * 二级页转场：新页从右侧滑入，**一级页整体缩小成一张带圆角的卡片**再被盖住。
 *
 * 官方 NavTransitions.MiuixDefault 只做「平移 1/4 宽 + 轻微透明」，底层页始终是铺满屏幕的矩形，
 * 所以点进二级页时没有纵深，像两张纸左右错开。这里沿用它的进入曲线（`NavProgrammaticEasing`，
 * 起手快、尾巴长），只把 covered 那一半换成缩放：`scale 1 → 0.91`、透明度 `1 → 0.85`、
 * 圆角 `0 → 28dp`（都在 `d` 上线性取值，随进度实时变化）。
 *
 * 圆角用 `graphicsLayer { shape = … ; clip = true }` 画，而不是 `Modifier.clip(shape)` —— 后者
 * shape 是静态的，半径没法跟着 `d` 长出来；graphicsLayer 的 shape 每帧读取，可以随动画渐进。
 * 进入层本身不在这里裁角：那一层由 [NavDisplayEffects.cornerClipRadius]（设备真实屏幕圆角 44dp）负责。
 *
 * 侧滑返回（predictive back）另有两处调整，都在手势期间生效：
 * - 位移按 `s^1.15` 压缩，手指划同样距离时页面走得少一点，露出的旧页更少；
 *   只在 `scope.gesture != null` 时用（库里在松手后的 settle 阶段仍保留 gesture 上下文，
 *   所以整段不会在「松手」那一刻跳一下），程序化 push/pop 仍走官方线性几何。
 * - 松手后的入位/回弹换成更软的弹簧（默认 stiffness 146 ≈ 500ms，这里 90/115），
 *   让收尾那一下不那么「嗖」。
 */
private object CardStackNavTransition : NavTransition {

    override val motion: NavMotion = NavMotion(
        commit = NavSettleSpec.Spring(stiffness = NavGestureCommitStiffness),
        cancel = NavSettleSpec.Spring(stiffness = NavGestureCancelStiffness),
        programmatic = NavSettleSpec.Tween(
            durationMillis = NavProgrammaticDurationMillis,
            easing = NavProgrammaticEasing
        )
    )

    override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier = graphicsLayer {
        val width = scope.layoutSize.width.toFloat()
        val d = scope.relativeDepth
        val rtl = scope.layoutDirection == LayoutDirection.Rtl
        if (d <= 0f) {
            // 进入层：整页从尾侧滑入。取整到物理像素，避免带圆角的边在分数偏移下出现一条抖动亮线。
            var s = (-d).coerceIn(0f, 1f)
            if (scope.gesture != null) s = s.pow(NavGestureTranslationPower)
            translationX = ((if (rtl) -1f else 1f) * s * width).roundToInt().toFloat()
        } else {
            // 被覆盖层：缩小、微微向左让位、变淡，并长出圆角，像一张卡片沉到下面。
            val p = d.coerceIn(0f, 1f)
            val scale = 1f - (1f - NavCardStackScale) * p
            scaleX = scale
            scaleY = scale
            translationX = (if (rtl) 1f else -1f) * width * NavCardStackShift * p
            alpha = 1f - (1f - NavCardStackAlpha) * p
            clip = p > 0.002f
            shape = RoundedCornerShape(NavCardStackCornerRadius * p)
        }
    }
}

/** 官方 500ms 的 1.5 倍：同样是「起手快、尾巴长」，只是整段更从容。 */
private const val NavProgrammaticDurationMillis = 750

/** 侧滑返回时页面位移的压缩指数：>1 表示同样手指距离下页面走得更少（露出更少）。 */
private const val NavGestureTranslationPower = 1.15f
/** 松手「回位」（真返回）用的弹簧刚度：默认 146 ≈ 500ms，90 ≈ 630ms。 */
private const val NavGestureCommitStiffness = 90f
/** 松手「取消返回」用的弹簧刚度：比回位更软一点，弹回原位更缓。 */
private const val NavGestureCancelStiffness = 115f

/** 一级页被盖住时的最终缩放（1 - 该值 = 缩到多少）。 */
private const val NavCardStackScale = 0.91f
/** 缩小时向左让出的宽度比例（相对屏宽），给滑入的新页腾出一点透视余量。 */
private const val NavCardStackShift = 0.02f
/** 一级页被盖住时的最终不透明度。 */
private const val NavCardStackAlpha = 0.85f
/** 一级页缩成卡片时的圆角，随进度从 0 长到这个值。 */
private val NavCardStackCornerRadius = 28.dp

@Composable
internal fun HomeScreen(
    enableBatteryRing: Boolean,
    onEnableBatteryRingChange: (Boolean) -> Unit,
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    autoWallpaperInterval: AutoWallpaperInterval,
    autoWallpaperOrder: AutoWallpaperOrder,
    rearScreenOffTimeout: RearScreenOffTimeout,
    rearScreenSchedule: RearScreenSchedulePrefs,
    keepRearScreenOnInGame: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    bottomBarBlur: Boolean,
    showFunctionCount: Boolean,
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
    onAutoWallpaperIntervalChange: (AutoWallpaperInterval) -> Unit,
    onAutoWallpaperOrderChange: (AutoWallpaperOrder) -> Unit,
    onRearScreenOffTimeoutChange: (RearScreenOffTimeout) -> Unit,
    onRearScreenScheduleChange: (RearScreenSchedulePrefs) -> Unit,
    onKeepRearScreenOnInGameChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onShowFunctionCountChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onDoubleTapWakeDisabledPackagesChange: (String) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onRestartScopes: (List<String>) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    onBackupPreferences: () -> Unit,
    onRestorePreferences: () -> Unit,
    batteryColorIdle: String,
    batteryColorCharging: String,
    batteryColorLow: String,
    batteryPercentPosition: BatteryPercentPosition,
    batteryLayout: BatteryRingLayout,
    onBatteryColorChange: (BatteryColorSlot, String) -> Unit,
    onBatteryPercentPositionChange: (BatteryPercentPosition) -> Unit,
    onBatteryLayoutChange: (BatteryRingLayout) -> Unit
) {
    val enabledFunctionCount = countEnabledFunctions(
        enableBatteryRing = enableBatteryRing,
        batteryRingSupported = BatteryRingGeometry.isCalibratedDevice(Build.DEVICE),
        enable18ProFeatures = enable18ProFeatures,
        disableLongPress = disableLongPress,
        removeWallpaperLimit = removeWallpaperLimit,
        removeAppCardLimit = removeAppCardLimit,
        autoWallpaperEnabled = autoWallpaperInterval != AutoWallpaperInterval.OFF,
        rearScreenOffTimeoutActive = rearScreenOffTimeout != RearScreenOffTimeout.SYSTEM
                || rearScreenSchedule.enabled,
        keepRearScreenOnInGame = keepRearScreenOnInGame,
        fixRearScreenApply = fixRearScreenApply,
        faceEnrollmentCompat = faceEnrollmentCompat,
        disableRearScreenCover = disableRearScreenCover,
        disableDoubleTapWake = disableDoubleTapWake,
    )
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
    // 官方 example/AppContent.kt 的配法：把设备真实屏幕圆角（这台机器 44dp）喂给
    // cornerClipRadius，二级页滑入时四角跟着屏幕圆角裁成一张卡片；压暗保持官方默认 0.5。
    val navCornerRadius = rememberNavSystemCornerRadius()
    val navBackdropColor = MiuixTheme.colorScheme.surface
    val navEffects = remember(navCornerRadius, navBackdropColor) {
        NavDisplayEffects(
            // 不吞输入：转场拉长到 750ms、松手弹簧也放缓之后，blockInputDuringTransition 会让
            // 「刚返回就点」的第一下落在还没 settle 的层上被丢弃（用户实测要点两次）。
            // 连点由 openDetail 的 `lastOrNull() == Main` 守卫拦重复入栈。
            blockInputDuringTransition = false,
            cornerClipRadius = navCornerRadius,
            backdropColor = navBackdropColor
        )
    }
    Box(Modifier.fillMaxSize()) {
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = navigateBack,
        transition = CardStackNavTransition,
        effects = navEffects
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
                        showFunctionCount = showFunctionCount,
                        appLanguage = appLanguage,
                        checkUpdates = checkUpdates,
                        enableAppCard = enableAppCard,
                        launcherIconHidden = launcherIconHidden,
                        onFloatingNavBarChange = onFloatingNavBarChange,
                        onLiquidGlassChange = onLiquidGlassChange,
                        onBottomBarBlurChange = onBottomBarBlurChange,
                        onShowFunctionCountChange = onShowFunctionCountChange,
                        onAppLanguageChange = onAppLanguageChange,
                        onCheckUpdatesChange = onCheckUpdatesChange,
                        onEnableAppCardChange = onEnableAppCardChange,
                        onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                        onBackupPreferences = onBackupPreferences,
                        onRestorePreferences = onRestorePreferences,
                        onBack = navigateBack
                    )
                    HomeRoute.License -> LicensePage(onBack = navigateBack)
                    HomeRoute.Donate -> DonatePage(onBack = navigateBack)
                    HomeRoute.AppPicker -> AppPickerPage(
                        selectedPackages = doubleTapWakeDisabledPackages,
                        onSelectedPackagesChange = onDoubleTapWakeDisabledPackagesChange,
                        onBack = navigateBack
                    )
                    HomeRoute.BatteryColor -> BatteryColorPage(
                        enableBatteryRing = enableBatteryRing,
                        onEnableBatteryRingChange = onEnableBatteryRingChange,
                        idleColor = batteryColorIdle,
                        chargingColor = batteryColorCharging,
                        lowColor = batteryColorLow,
                        percentPosition = batteryPercentPosition,
                        layout = batteryLayout,
                        onColorChange = onBatteryColorChange,
                        onPercentPositionChange = onBatteryPercentPositionChange,
                        onLayoutCommit = onBatteryLayoutChange,
                        onBack = navigateBack
                    )
                    HomeRoute.RearScreenTiming -> RearScreenTimingPage(
                        rearScreenOffTimeout = rearScreenOffTimeout,
                        rearScreenSchedule = rearScreenSchedule,
                        keepRearScreenOnInGame = keepRearScreenOnInGame,
                        autoWallpaperInterval = autoWallpaperInterval,
                        autoWallpaperOrder = autoWallpaperOrder,
                        onRearScreenOffTimeoutChange = onRearScreenOffTimeoutChange,
                        onRearScreenScheduleChange = onRearScreenScheduleChange,
                        onKeepRearScreenOnInGameChange = onKeepRearScreenOnInGameChange,
                        onAutoWallpaperIntervalChange = onAutoWallpaperIntervalChange,
                        onAutoWallpaperOrderChange = onAutoWallpaperOrderChange,
                        onBack = navigateBack
                    )
                    HomeRoute.Main -> MainContent(
                        enable18ProFeatures = enable18ProFeatures,
                        onEnable18ProFeaturesChange = onEnable18ProFeaturesChange,
                        enabledFunctionCount = enabledFunctionCount,
                        selected = selected,
                        homeListState = homeListState,
                        functionListState = functionListState,
                        aboutListState = aboutListState,
                        onSelectedChange = { selected = it },
                        disableLongPress = disableLongPress,
                        removeWallpaperLimit = removeWallpaperLimit,
                        removeAppCardLimit = removeAppCardLimit,
                        fixRearScreenApply = fixRearScreenApply,
                        faceEnrollmentCompat = faceEnrollmentCompat,
                        floatingNavBar = floatingNavBar,
                        liquidGlass = liquidGlass,
                        bottomBarBlur = bottomBarBlur,
                        showFunctionCount = showFunctionCount,
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
                        onFaceEnrollmentCompatChange = onFaceEnrollmentCompatChange,
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
                        onRestartScopes = onRestartScopes,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                        onBatteryColorClick = { openDetail(HomeRoute.BatteryColor) },
                        onRearScreenTimingClick = { openDetail(HomeRoute.RearScreenTiming) }
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
    showFunctionCount: Boolean,
    appLanguage: AppLanguage,
    checkUpdates: Boolean,
    enableAppCard: Boolean,
    launcherIconHidden: Boolean,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onShowFunctionCountChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    onBackupPreferences: () -> Unit,
    onRestorePreferences: () -> Unit,
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
                        showFunctionCount = showFunctionCount,
                        appLanguage = appLanguage,
                        checkUpdates = checkUpdates,
                        enableAppCard = enableAppCard,
                        launcherIconHidden = launcherIconHidden,
                        onFloatingNavBarChange = onFloatingNavBarChange,
                        onLiquidGlassChange = onLiquidGlassChange,
                        onBottomBarBlurChange = onBottomBarBlurChange,
                        onShowFunctionCountChange = onShowFunctionCountChange,
                        onAppLanguageChange = onAppLanguageChange,
                        onCheckUpdatesChange = onCheckUpdatesChange,
                        onEnableAppCardChange = onEnableAppCardChange,
                        onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                        onBackupPreferences = onBackupPreferences,
                        onRestorePreferences = onRestorePreferences
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
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    tab: HomeNavigationPolicy.Tab,
    listState: LazyListState,
    moduleActivated: Boolean,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    themeMode: ThemeMode,
    onDonateClick: () -> Unit,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onAddDisabledAppsClick: () -> Unit,
    onBatteryColorClick: () -> Unit,
    onRearScreenTimingClick: () -> Unit,
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                val tabLabelRes = navItems.first { it.tab == tab }.labelRes
                TopAppBar(
                    title = stringResource(tabLabelRes),
                    largeTitle = stringResource(tabLabelRes),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        val topAction = HomeNavigationPolicy.topActionFor(tab)
                        IconButton(
                            onClick = {
                                when (topAction) {
                                    HomeNavigationPolicy.TopAction.RESTART -> onRestartClick()
                                    HomeNavigationPolicy.TopAction.SETTINGS -> onSettingsClick()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = when (topAction) {
                                    HomeNavigationPolicy.TopAction.RESTART -> MiuixIcons.Refresh
                                    HomeNavigationPolicy.TopAction.SETTINGS -> MiuixIcons.Settings
                                },
                                contentDescription = stringResource(when (topAction) {
                                    HomeNavigationPolicy.TopAction.RESTART -> R.string.restart_scope
                                    HomeNavigationPolicy.TopAction.SETTINGS -> R.string.settings_title
                                }),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
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
                            enable18ProFeatures = enable18ProFeatures,
                            onEnable18ProFeaturesChange = onEnable18ProFeaturesChange,
                            disableLongPress = disableLongPress,
                            removeWallpaperLimit = removeWallpaperLimit,
                            removeAppCardLimit = removeAppCardLimit,
                            fixRearScreenApply = fixRearScreenApply,
                            faceEnrollmentCompat = faceEnrollmentCompat,
                            disableRearScreenCover = disableRearScreenCover,
                            doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                            onDisableLongPressChange = onDisableLongPressChange,
                            onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                            onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                            onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                            onFaceEnrollmentCompatChange = onFaceEnrollmentCompatChange,
                            onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                            onAddDisabledAppsClick = onAddDisabledAppsClick,
                            onBatteryColorClick = onBatteryColorClick,
                            onRearScreenTimingClick = onRearScreenTimingClick
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
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    enabledFunctionCount: Int,
    selected: HomeNavigationPolicy.Tab,
    homeListState: LazyListState,
    functionListState: LazyListState,
    aboutListState: LazyListState,
    onSelectedChange: (HomeNavigationPolicy.Tab) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    bottomBarBlur: Boolean,
    showFunctionCount: Boolean,
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
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
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
    onRestartScopes: (List<String>) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    onBatteryColorClick: () -> Unit,
    onRearScreenTimingClick: () -> Unit
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
    val functionBadge: (@Composable () -> Unit)? = if (showFunctionCount && enabledFunctionCount > 0) {
        { FunctionCountBadge(enabledFunctionCount) }
    } else null
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
                enable18ProFeatures = enable18ProFeatures,
                onEnable18ProFeaturesChange = onEnable18ProFeaturesChange,
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
                faceEnrollmentCompat = faceEnrollmentCompat,
                disableRearScreenCover = disableRearScreenCover,
                doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                themeMode = themeMode,
                onDonateClick = onDonateClick,
                onDisableLongPressChange = onDisableLongPressChange,
                onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                onFaceEnrollmentCompatChange = onFaceEnrollmentCompatChange,
                onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                onAddDisabledAppsClick = onAddDisabledAppsClick,
                onBatteryColorClick = onBatteryColorClick,
                onRearScreenTimingClick = onRearScreenTimingClick,
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
                            badge = if (item.tab == HomeNavigationPolicy.Tab.FUNCTION) functionBadge else null,
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
                        isBlurActive = bottomBarBlur,
                        badge = { index ->
                            if (navItems[index].tab == HomeNavigationPolicy.Tab.FUNCTION) functionBadge else null
                        }
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
                                badge = if (item.tab == HomeNavigationPolicy.Tab.FUNCTION) functionBadge else null,
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
                    val selected = restartScopeItems.filter { checkedItems[it.packageName] == true }
                        .map { it.packageName }
                    if (selected.isNotEmpty()) onRestartScopes(selected)
                    checkedItems.clear()
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                content = { Text(stringResource(R.string.common_confirm)) }
            )
        }
    }
}
