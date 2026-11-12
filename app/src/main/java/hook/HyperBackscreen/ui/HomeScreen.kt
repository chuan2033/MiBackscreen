package hook.HyperBackscreen.ui

import hook.HyperBackscreen.model.AutoWallpaperInterval
import hook.HyperBackscreen.model.AutoWallpaperOrder
import hook.HyperBackscreen.model.RearScreenOffTimeout
import hook.HyperBackscreen.model.RearScreenSchedulePrefs
import hook.HyperBackscreen.ui.function.FunctionGroupCounts
import hook.HyperBackscreen.ui.function.RearMediaSwitch
import hook.HyperBackscreen.ui.function.RearMediaSwitches
import hook.HyperBackscreen.ui.function.countEnabledFunctions
import hook.HyperBackscreen.ui.function.countFunctionGroups
import hook.HyperBackscreen.ui.navigation.HomeNavigationPolicy
import hook.HyperBackscreen.ui.navigation.HomeRoute
import hook.HyperBackscreen.ui.components.AppDialog
import hook.HyperBackscreen.ui.components.LocalPageBackdrop
import hook.HyperBackscreen.ui.config.ThemePage
import hook.HyperBackscreen.ui.theme.LocalThemeState
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import hook.HyperBackscreen.ui.home.ActivationState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import hook.HyperBackscreen.ui.theme.BottomBarMetrics
import hook.HyperBackscreen.ui.theme.BottomBarStyle
import hook.HyperBackscreen.ui.components.DialogButtonRow
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.AppGlassTopAppBar
import hook.HyperBackscreen.ui.components.TopBarIconButton
import hook.HyperBackscreen.ui.components.rememberPageScrollBehavior
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.util.AppLanguage
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.about.AboutPage
import hook.HyperBackscreen.ui.about.AppUpdatePage
import hook.HyperBackscreen.ui.about.DonatePage
import hook.HyperBackscreen.ui.about.LicensePage
import hook.HyperBackscreen.ui.about.VersionHistoryPage
import hook.HyperBackscreen.batteryring.BatteryRingGeometry
import hook.HyperBackscreen.ui.battery.BatteryColorPage
import hook.HyperBackscreen.ui.battery.BatteryColorSlot
import hook.HyperBackscreen.batteryring.BatteryPercentPosition
import hook.HyperBackscreen.batteryring.BatteryRingLayout
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
import hook.HyperBackscreen.ui.recorder.RearScreenRecordPage
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.config.ConfigPage
import hook.HyperBackscreen.ui.function.FunctionPage
import hook.HyperBackscreen.ui.function.RearDoubleTapPage
import hook.HyperBackscreen.ui.function.RearCastPage
import hook.HyperBackscreen.ui.function.RearMusicPage
import hook.HyperBackscreen.ui.function.RearWidgetPickerPage
import hook.HyperBackscreen.ui.function.RearWidgetsPage
import hook.HyperBackscreen.ui.function.RearScreenTimingPage
import hook.HyperBackscreen.ui.home.HomePage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Settings
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
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

/**
 * 点击进出二级页的时长：沿用官方 500ms。曾放慢到 750ms，频繁进出时显得拖沓；
 * 侧滑返回松手后的软弹簧（下面两个刚度）单独保留，不受这里影响。
 */
private const val NavProgrammaticDurationMillis = 500

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
private val NavCardStackCornerRadius = HomeUiTokens.CornerPage

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
    bottomBarStyle: BottomBarStyle,
    bottomBarBlur: Boolean,
    showFunctionCount: Boolean,
    appLanguage: AppLanguage,
    checkUpdates: Boolean,
    enableAppCard: Boolean,
    disableRearScreenCover: Boolean,
    disableDoubleTapWake: Boolean,
    doubleTapWakeDisabledPackages: String,
    doubleTapSleepDisabledPackages: String,
    musicWidgetWhitelistPackages: String,
    launcherIconHidden: Boolean,
    moduleActivated: ActivationState,
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
    onBottomBarStyleChange: (BottomBarStyle) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onShowFunctionCountChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onDoubleTapWakeDisabledPackagesChange: (String) -> Unit,
    onDoubleTapSleepDisabledPackagesChange: (String) -> Unit,
    onMusicWidgetWhitelistPackagesChange: (String) -> Unit,
    /** 音乐白名单选择页出栈时回调：名单改过就重启背屏中心让它生效。 */
    onMusicWidgetWhitelistPickerClosed: () -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onRestartScopes: (List<String>) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    settingsEntry: String,
    onSettingsEntryChange: (String) -> Unit,
    onBackupPreferences: () -> Unit,
    onRestorePreferences: () -> Unit,
    batteryColorIdle: String,
    batteryColorCharging: String,
    batteryColorLow: String,
    batteryPercentPosition: BatteryPercentPosition,
    batteryLayout: BatteryRingLayout,
    onBatteryColorChange: (BatteryColorSlot, String) -> Unit,
    onBatteryPercentPositionChange: (BatteryPercentPosition) -> Unit,
    onBatteryLayoutChange: (BatteryRingLayout) -> Unit,
    rearMedia: RearMediaSwitches,
    onRearMediaChange: (RearMediaSwitch, Boolean) -> Unit,
) {
    val doubleTapSleepFilterActive = remember(doubleTapSleepDisabledPackages) {
        PackageListCodec.parse(doubleTapSleepDisabledPackages).isNotEmpty()
    }
    val musicWidgetWhitelistActive = remember(musicWidgetWhitelistPackages) {
        PackageListCodec.parse(musicWidgetWhitelistPackages).isNotEmpty()
    }
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
        doubleTapSleepFilterActive = doubleTapSleepFilterActive,
        musicWidgetWhitelistActive = musicWidgetWhitelistActive,
        rearMedia = rearMedia,
    )
    val functionGroupCounts = countFunctionGroups(
        disableRearScreenCover = disableRearScreenCover,
        disableDoubleTapWake = disableDoubleTapWake,
        enableBatteryRing = enableBatteryRing,
        batteryRingSupported = BatteryRingGeometry.isCalibratedDevice(Build.DEVICE),
        disableLongPress = disableLongPress,
        removeAppCardLimit = removeAppCardLimit,
        autoWallpaperEnabled = autoWallpaperInterval != AutoWallpaperInterval.OFF,
        rearScreenOffTimeoutActive = rearScreenOffTimeout != RearScreenOffTimeout.SYSTEM
                || rearScreenSchedule.enabled,
        keepRearScreenOnInGame = keepRearScreenOnInGame,
        fixRearScreenApply = fixRearScreenApply,
        faceEnrollmentCompat = faceEnrollmentCompat,
        removeWallpaperLimit = removeWallpaperLimit,
        doubleTapSleepFilterActive = doubleTapSleepFilterActive,
        musicWidgetWhitelistActive = musicWidgetWhitelistActive,
        rearMedia = rearMedia,
    )
    var selected by rememberSaveable { mutableStateOf(HomeNavigationPolicy.Tab.HOME) }
    val backStack = rememberNavBackStack<HomeRoute>(HomeRoute.Main)
    val homeListState = rememberLazyListState()
    val functionListState = rememberLazyListState()
    val aboutListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // 音乐白名单页出栈（返回键 / 手势 / 页内返回都算）时通知上层：选择期间不逐项重启背屏中心，
    // 离开页面后统一重启一次。
    val musicPickerOpen = HomeRoute.MusicWhitelistAppPicker in backStack
    val onMusicPickerClosed by rememberUpdatedState(onMusicWidgetWhitelistPickerClosed)
    LaunchedEffect(musicPickerOpen) {
        if (!musicPickerOpen) onMusicPickerClosed()
    }
    // Covered entries can remain composed in miuix-nav 0.9.4; release their input focus.
    LaunchedEffect(backStack.lastOrNull()) {
        focusManager.clearFocus()
    }
    // 系统返回手势 / 返回键：始终作用于栈顶。
    val navigateBack: () -> Unit = {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
    // 页面内的返回按钮：转场期间不拦输入，正在退场的页面仍能响应点击。
    // 只有调用方页面还在栈顶时才出栈，避免快速连点两次把上一层也一起退掉。
    fun navigateBackFrom(page: HomeRoute) {
        if (backStack.size > 1 && backStack.lastOrNull() == page) backStack.removeLastOrNull()
    }
    // 入栈只认调用方页面：只有 [from] 仍在栈顶时才进入 [route]。滑入转场期间
    // 再点露出来的下层页面不会叠出 Main → A → B 这样的栈，也拦掉了同名重复入栈。
    fun openDetail(from: HomeRoute, route: HomeRoute) {
        if (backStack.lastOrNull() == from && from != route) backStack.add(route)
    }

    val feedback = LocalUiFeedback.current
    // 官方 example/AppContent.kt 的配法：把设备真实屏幕圆角（这台机器 44dp）喂给
    // cornerClipRadius，二级页滑入时四角跟着屏幕圆角裁成一张卡片；压暗保持官方默认 0.5。
    val navCornerRadius = rememberNavSystemCornerRadius()
    val navBackdropColor = MiuixTheme.colorScheme.surface
    val navEffects = remember(navCornerRadius, navBackdropColor) {
        NavDisplayEffects(
            // 不吞输入：松手弹簧放缓之后，blockInputDuringTransition 会让
            // 「刚返回就点」的第一下落在还没 settle 的层上被丢弃（用户实测要点两次）。
            // 连点与转场期间的误触由 openDetail / navigateBackFrom 的「调用方必须在栈顶」守卫拦住。
            blockInputDuringTransition = false,
            cornerClipRadius = navCornerRadius,
            backdropColor = navBackdropColor
        )
    }
    // 全屏横滑返回（移植自 KernelSU 的「横移返回手势」）：要在 NavDisplay 的构建 lambda 外读，
    // NavDisplay 只在构建 lambda 变化时重建 entry，值得由 lambda 捕获才会跟着开关更新。
    val swipeBackDirection = when {
        LocalThemeState.current?.swipeBack != true -> null
        LocalLayoutDirection.current == LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
        else -> NavSwipeDirection.LeftToRight
    }
    Box(Modifier.fillMaxSize()) {
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = navigateBack,
        transition = CardStackNavTransition,
        effects = navEffects
    ) {
        entry<HomeRoute>(swipeDismiss = swipeBackDirection) { page ->
            // A covered entry stays alive for back navigation, but must not expose hidden controls.
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (page != backStack.lastOrNull()) Modifier.clearAndSetSemantics {} else Modifier
                )
            ) {
                when (page) {
                    HomeRoute.Settings -> SettingsPage(
                        appLanguage = appLanguage,
                        enableAppCard = enableAppCard,
                        launcherIconHidden = launcherIconHidden,
                        onThemeClick = { openDetail(page, HomeRoute.Theme) },
                        onAppLanguageChange = onAppLanguageChange,
                        onEnableAppCardChange = onEnableAppCardChange,
                        onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                        settingsEntry = settingsEntry,
                        onSettingsEntryChange = onSettingsEntryChange,
                        onBackupPreferences = onBackupPreferences,
                        onRestorePreferences = onRestorePreferences,
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.Theme -> ThemePage(
                        bottomBarStyle = bottomBarStyle,
                        bottomBarBlur = bottomBarBlur,
                        showFunctionCount = showFunctionCount,
                        onBottomBarStyleChange = onBottomBarStyleChange,
                        onBottomBarBlurChange = onBottomBarBlurChange,
                        onShowFunctionCountChange = onShowFunctionCountChange,
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.License -> LicensePage(onBack = { navigateBackFrom(page) })
                    HomeRoute.Donate -> DonatePage(onBack = { navigateBackFrom(page) })
                    HomeRoute.AppPicker -> AppPickerPage(
                        selectedPackages = doubleTapWakeDisabledPackages,
                        onSelectedPackagesChange = onDoubleTapWakeDisabledPackagesChange,
                        onBack = { navigateBackFrom(page) },
                        titleRes = R.string.config_disable_double_tap_wake_title,
                        hintRes = R.string.app_picker_double_tap_hint
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
                        onBack = { navigateBackFrom(page) }
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
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.AppUpdate -> AppUpdatePage(
                        checkUpdates = checkUpdates,
                        onCheckUpdatesChange = onCheckUpdatesChange,
                        onVersionHistoryClick = { openDetail(page, HomeRoute.VersionHistory) },
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.VersionHistory -> VersionHistoryPage(onBack = { navigateBackFrom(page) })
                    HomeRoute.RearDoubleTap -> RearDoubleTapPage(
                        doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                        doubleTapSleepDisabledPackages = doubleTapSleepDisabledPackages,
                        onWakeAppsClick = { openDetail(page, HomeRoute.AppPicker) },
                        onSleepAppsClick = { openDetail(page, HomeRoute.DoubleTapSleepAppPicker) },
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.DoubleTapSleepAppPicker -> AppPickerPage(
                        selectedPackages = doubleTapSleepDisabledPackages,
                        onSelectedPackagesChange = onDoubleTapSleepDisabledPackagesChange,
                        onBack = { navigateBackFrom(page) },
                        titleRes = R.string.double_tap_sleep_title,
                        hintRes = R.string.double_tap_sleep_picker_hint
                    )
                    HomeRoute.MusicWhitelistAppPicker -> AppPickerPage(
                        selectedPackages = musicWidgetWhitelistPackages,
                        onSelectedPackagesChange = onMusicWidgetWhitelistPackagesChange,
                        onBack = { navigateBackFrom(page) },
                        titleRes = R.string.music_widget_whitelist_title,
                        hintRes = R.string.music_widget_whitelist_picker_hint
                    )
                    HomeRoute.RearMusic -> RearMusicPage(
                        musicWidgetWhitelistPackages = musicWidgetWhitelistPackages,
                        rearMedia = rearMedia,
                        onRearMediaChange = onRearMediaChange,
                        onWhitelistClick = { openDetail(page, HomeRoute.MusicWhitelistAppPicker) },
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.RearScreenRecord -> RearScreenRecordPage(onBack = { navigateBackFrom(page) })
                    HomeRoute.RearWidgets -> RearWidgetsPage(
                        onAddClick = { openDetail(page, HomeRoute.RearWidgetPicker) },
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.RearWidgetPicker -> RearWidgetPickerPage(onBack = { navigateBackFrom(page) })
                    // 功能页已不放入口；路由只追加不删，保留它让旧版本保存的返回栈仍能恢复到这一页。
                    HomeRoute.RearCast -> RearCastPage(
                        onRearScreenTimingClick = { openDetail(page, HomeRoute.RearScreenTiming) },
                        onBack = { navigateBackFrom(page) }
                    )
                    HomeRoute.Main -> MainContent(
                        enable18ProFeatures = enable18ProFeatures,
                        onEnable18ProFeaturesChange = onEnable18ProFeaturesChange,
                        enabledFunctionCount = enabledFunctionCount,
                        functionGroupCounts = functionGroupCounts,
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
                        bottomBarStyle = bottomBarStyle,
                        bottomBarBlur = bottomBarBlur,
                        showFunctionCount = showFunctionCount,
                        appLanguage = appLanguage,
                        disableRearScreenCover = disableRearScreenCover,
                        doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                        doubleTapSleepDisabledPackages = doubleTapSleepDisabledPackages,
                        musicWidgetWhitelistPackages = musicWidgetWhitelistPackages,
                        launcherIconHidden = launcherIconHidden,
                        moduleActivated = moduleActivated,
                        onDisableLongPressChange = onDisableLongPressChange,
                        onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                        onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                        onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                        onFaceEnrollmentCompatChange = onFaceEnrollmentCompatChange,
                        onBottomBarBlurChange = onBottomBarBlurChange,
                        onAppLanguageChange = onAppLanguageChange,
                        onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                        onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                        onSettingsClick = { openDetail(page, HomeRoute.Settings) },
                        onRearDoubleTapClick = { openDetail(page, HomeRoute.RearDoubleTap) },
                        onRearMusicClick = { openDetail(page, HomeRoute.RearMusic) },
                        onRearWidgetsClick = { openDetail(page, HomeRoute.RearWidgets) },
                        onRearScreenRecordClick = { openDetail(page, HomeRoute.RearScreenRecord) },
                        onLicenseClick = { openDetail(page, HomeRoute.License) },
                        onDonateClick = { openDetail(page, HomeRoute.Donate) },
                        onAppUpdateClick = { openDetail(page, HomeRoute.AppUpdate) },
                        onRestartScopes = onRestartScopes,
                        themeSettingsShortcut = themeSettingsShortcut,
                        onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                        settingsEntry = settingsEntry,
                        onSettingsEntryChange = onSettingsEntryChange,
                        onBatteryColorClick = { openDetail(page, HomeRoute.BatteryColor) },
                        onRearScreenTimingClick = { openDetail(page, HomeRoute.RearScreenTiming) },
                        rearMedia = rearMedia,
                        onRearMediaChange = onRearMediaChange,
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
                    .padding(
                        bottom = BottomBarMetrics.snackbarBottomPadding(
                            onMainPage = backStack.lastOrNull() == HomeRoute.Main,
                            floatingBar = bottomBarStyle.isFloating
                        )
                    )
            )
        }
    }

}

@Composable
private fun SettingsPage(
    appLanguage: AppLanguage,
    enableAppCard: Boolean,
    launcherIconHidden: Boolean,
    onThemeClick: () -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    settingsEntry: String,
    onSettingsEntryChange: (String) -> Unit,
    onBackupPreferences: () -> Unit,
    onRestorePreferences: () -> Unit,
    onBack: () -> Unit
) {
    DetailPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        topSpacing = 0.dp,
    ) {
        item {
            ConfigPage(
                appLanguage = appLanguage,
                enableAppCard = enableAppCard,
                launcherIconHidden = launcherIconHidden,
                onThemeClick = onThemeClick,
                onAppLanguageChange = onAppLanguageChange,
                onEnableAppCardChange = onEnableAppCardChange,
                onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                themeSettingsShortcut = themeSettingsShortcut,
                onThemeSettingsShortcutChange = onThemeSettingsShortcutChange,
                settingsEntry = settingsEntry,
                onSettingsEntryChange = onSettingsEntryChange,
                onBackupPreferences = onBackupPreferences,
                onRestorePreferences = onRestorePreferences
            )
        }
    }
}

@Composable
private fun MainTabPage(
    functionGroupCounts: FunctionGroupCounts,
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    tab: HomeNavigationPolicy.Tab,
    isCurrentPage: Boolean,
    listState: LazyListState,
    moduleActivated: ActivationState,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    doubleTapSleepDisabledPackages: String,
    musicWidgetWhitelistPackages: String,
    onDonateClick: () -> Unit,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onRearDoubleTapClick: () -> Unit,
    onRearMusicClick: () -> Unit,
    onRearWidgetsClick: () -> Unit,
    onRearScreenRecordClick: () -> Unit,
    onBatteryColorClick: () -> Unit,
    onRearScreenTimingClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onAppUpdateClick: () -> Unit,
    onRestartClick: () -> Unit,
    onSettingsClick: () -> Unit,
    rearMedia: RearMediaSwitches,
    onRearMediaChange: (RearMediaSwitch, Boolean) -> Unit,
) {
    val scrollBehavior = rememberPageScrollBehavior(listState)
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
            val tabLabelRes = navItems.first { it.tab == tab }.labelRes
            val isContentScrolled = scrollBehavior.state.heightOffset < 0f || listState.canScrollBackward
            AppGlassTopAppBar(
                title = stringResource(tabLabelRes),
                isContentScrolled = isContentScrolled,
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
                actions = {
                    val topAction = HomeNavigationPolicy.topActionFor(tab)
                    TopBarIconButton(
                        onClick = {
                            when (topAction) {
                                HomeNavigationPolicy.TopAction.RESTART -> onRestartClick()
                                HomeNavigationPolicy.TopAction.SETTINGS -> onSettingsClick()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = when (topAction) {
                                HomeNavigationPolicy.TopAction.RESTART -> MiuixIcons.Glass.Refresh
                                HomeNavigationPolicy.TopAction.SETTINGS -> MiuixIcons.Glass.Settings
                            },
                            contentDescription = stringResource(when (topAction) {
                                HomeNavigationPolicy.TopAction.RESTART -> R.string.restart_scope
                                HomeNavigationPolicy.TopAction.SETTINGS -> R.string.settings_title
                            }),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
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
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                // 左右留白放进 contentPadding：给列表本身加 padding 会把卡片阴影在两侧裁出竖直灰边。
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = innerPadding.calculateTopPadding()
                )
            ) {
                when (tab) {
                    HomeNavigationPolicy.Tab.HOME -> item {
                        HomePage(
                            moduleActivated = moduleActivated,
                            visible = isCurrentPage,
                            onDonateClick = onDonateClick
                        )
                    }
                    HomeNavigationPolicy.Tab.FUNCTION -> item {
                        FunctionPage(
                            groupCounts = functionGroupCounts,
                            enable18ProFeatures = enable18ProFeatures,
                            onEnable18ProFeaturesChange = onEnable18ProFeaturesChange,
                            disableLongPress = disableLongPress,
                            removeWallpaperLimit = removeWallpaperLimit,
                            removeAppCardLimit = removeAppCardLimit,
                            fixRearScreenApply = fixRearScreenApply,
                            faceEnrollmentCompat = faceEnrollmentCompat,
                            disableRearScreenCover = disableRearScreenCover,
                            doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
                            doubleTapSleepDisabledPackages = doubleTapSleepDisabledPackages,
                            musicWidgetWhitelistPackages = musicWidgetWhitelistPackages,
                            onDisableLongPressChange = onDisableLongPressChange,
                            onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                            onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                            onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                            onFaceEnrollmentCompatChange = onFaceEnrollmentCompatChange,
                            onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                            onRearDoubleTapClick = onRearDoubleTapClick,
                            onRearMusicClick = onRearMusicClick,
                            onRearWidgetsClick = onRearWidgetsClick,
                            onRearScreenRecordClick = onRearScreenRecordClick,
                            onBatteryColorClick = onBatteryColorClick,
                            onRearScreenTimingClick = onRearScreenTimingClick,
                            rearMedia = rearMedia,
                            onRearMediaChange = onRearMediaChange,
                        )
                    }
                    HomeNavigationPolicy.Tab.ABOUT -> item {
                        AboutPage(
                            onLicenseClick = onLicenseClick,
                            onAppUpdateClick = onAppUpdateClick
                        )
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
}

@Composable
private fun MainContent(
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    enabledFunctionCount: Int,
    functionGroupCounts: FunctionGroupCounts,
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
    bottomBarStyle: BottomBarStyle,
    bottomBarBlur: Boolean,
    showFunctionCount: Boolean,
    appLanguage: AppLanguage,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    doubleTapSleepDisabledPackages: String,
    musicWidgetWhitelistPackages: String,
    launcherIconHidden: Boolean,
    moduleActivated: ActivationState,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    onRearDoubleTapClick: () -> Unit,
    onRearMusicClick: () -> Unit,
    onRearWidgetsClick: () -> Unit,
    onRearScreenRecordClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onDonateClick: () -> Unit,
    onAppUpdateClick: () -> Unit,
    onRestartScopes: (List<String>) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    settingsEntry: String,
    onSettingsEntryChange: (String) -> Unit,
    onBatteryColorClick: () -> Unit,
    onRearScreenTimingClick: () -> Unit,
    rearMedia: RearMediaSwitches,
    onRearMediaChange: (RearMediaSwitch, Boolean) -> Unit,
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
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                functionGroupCounts = functionGroupCounts,
                enable18ProFeatures = enable18ProFeatures,
                onEnable18ProFeaturesChange = onEnable18ProFeaturesChange,
                tab = tab,
                isCurrentPage = page == pagerState.currentPage,
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
                doubleTapSleepDisabledPackages = doubleTapSleepDisabledPackages,
                musicWidgetWhitelistPackages = musicWidgetWhitelistPackages,
                onDonateClick = onDonateClick,
                onDisableLongPressChange = onDisableLongPressChange,
                onRemoveWallpaperLimitChange = onRemoveWallpaperLimitChange,
                onRemoveAppCardLimitChange = onRemoveAppCardLimitChange,
                onFixRearScreenApplyChange = onFixRearScreenApplyChange,
                onFaceEnrollmentCompatChange = onFaceEnrollmentCompatChange,
                onDisableRearScreenCoverChange = onDisableRearScreenCoverChange,
                onRearDoubleTapClick = onRearDoubleTapClick,
                onRearMusicClick = onRearMusicClick,
                onRearWidgetsClick = onRearWidgetsClick,
                onRearScreenRecordClick = onRearScreenRecordClick,
                onBatteryColorClick = onBatteryColorClick,
                onRearScreenTimingClick = onRearScreenTimingClick,
                onLicenseClick = onLicenseClick,
                onAppUpdateClick = onAppUpdateClick,
                onRestartClick = { showRestartDialog = true },
                onSettingsClick = onSettingsClick,
                rearMedia = rearMedia,
                onRearMediaChange = onRearMediaChange,
            )
        }

            if (bottomBarStyle == BottomBarStyle.STANDARD) {
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

            if (bottomBarStyle == BottomBarStyle.LIQUID) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = BottomBarMetrics.FloatingGap)
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

            if (bottomBarStyle == BottomBarStyle.GLASS) {
                GlassNavigationBar(
                    items = navItems.map { item ->
                        val label = stringResource(item.labelRes)
                        GlassNavigationItem(
                            icon = item.icon,
                            label = if (showFunctionCount && enabledFunctionCount > 0 &&
                                item.tab == HomeNavigationPolicy.Tab.FUNCTION
                            ) "$label ($enabledFunctionCount)" else label,
                        )
                    },
                    selectedIndex = navigationIndex,
                    onSelect = ::selectPage,
                    backdrop = bottomBarBackdrop.takeIf { bottomBarBlur },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp),
                )
            }

            // Miuix 原生悬浮底栏显示图标与标签。
            if (bottomBarStyle == BottomBarStyle.FLOATING) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // miuix 悬浮底栏自己处理导航栏 inset，这里只把它的固定留白折算成统一间距。
                        .offset(y = BottomBarMetrics.miuixFloatingBarOffset(navigationInset))
                ) {
                    FloatingNavigationBar(
                        modifier = Modifier.then(
                            if (bottomBarBlur) {
                                Modifier.textureBlur(
                                    backdrop = bottomBarBackdrop,
                                    shape = RoundedCornerShape(HomeUiTokens.CornerPage),
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
                        cornerRadius = HomeUiTokens.CornerPage,
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

    AppDialog(
        show = showRestartDialog,
        title = stringResource(R.string.restart_title),
        onDismissRequest = { showRestartDialog = false },
        // 不管怎么关的（确认、点外部、返回），退场动画结束后都清空勾选：
        // 否则下次打开「系统框架」仍是勾选状态，误点确认会直接重启手机。放在这里而不是
        // onDismissRequest，退场动画期间勾选框不会先闪一下变成未选。
        onDismissFinished = { checkedItems.clear() }
    ) {
        // 勾选「系统框架」执行的是重启手机而不是停止进程，选中时确认按钮要把后果写明。
        val anySelected = restartScopeItems.any { checkedItems[it.packageName] == true }
        val rebootSelected = checkedItems[Constants.SYSTEM_PACKAGE] == true
        val rebootButtonColor by animateColorAsState(
            targetValue = if (rebootSelected) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
            animationSpec = tween(200),
            label = "restartConfirmColor"
        )
        val rebootButtonContent by animateColorAsState(
            targetValue = if (rebootSelected) MiuixTheme.colorScheme.onError else MiuixTheme.colorScheme.onPrimary,
            animationSpec = tween(200),
            label = "restartConfirmContent"
        )
        restartScopeItems.forEach { item ->
            CheckboxPreference(
                title = stringResource(item.labelRes),
                summary = if (item.packageName == Constants.SYSTEM_PACKAGE) {
                    stringResource(R.string.restart_system_summary)
                } else {
                    null
                },
                checked = checkedItems[item.packageName] == true,
                onCheckedChange = { checked ->
                    checkedItems[item.packageName] = checked
                },
                checkboxLocation = CheckboxLocation.End
            )
        }

        DialogButtonRow(
            modifier = Modifier.padding(top = 12.dp),
            secondaryText = stringResource(R.string.common_select_all),
            onSecondary = {
                val allChecked = restartScopeItems.all { checkedItems[it.packageName] == true }
                restartScopeItems.forEach { checkedItems[it.packageName] = !allChecked }
            },
            primaryText = stringResource(
                if (rebootSelected) R.string.restart_device_confirm else R.string.common_confirm
            ),
            primaryEnabled = anySelected,
            // 勾了「系统框架」会直接重启手机：主按钮渐变成错误色，和普通「确定」区分开。
            primaryColors = ButtonDefaults.buttonColorsPrimary(
                color = rebootButtonColor,
                contentColor = rebootButtonContent
            ),
            onPrimary = {
                showRestartDialog = false
                val selected = restartScopeItems.filter { checkedItems[it.packageName] == true }
                    .map { it.packageName }
                if (selected.isNotEmpty()) onRestartScopes(selected)
            }
        )
    }
}
