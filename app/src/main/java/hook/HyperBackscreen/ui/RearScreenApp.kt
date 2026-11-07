package hook.HyperBackscreen.ui

import hook.HyperBackscreen.model.AutoWallpaperInterval
import hook.HyperBackscreen.model.AutoWallpaperOrder
import hook.HyperBackscreen.model.RearScreenOffTimeout
import hook.HyperBackscreen.model.RearScreenSchedulePrefs
import hook.HyperBackscreen.ui.util.LauncherIconController
import hook.HyperBackscreen.ui.util.scopeActionCommands
import hook.HyperBackscreen.ui.home.ActivationState
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DialogButtonRow
import hook.HyperBackscreen.ui.theme.LocalAppColors
import hook.HyperBackscreen.ui.theme.appColors
import android.graphics.Color
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import hook.HyperBackscreen.ui.components.rememberUiFeedback
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Job
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hook.HyperBackscreen.R
import hook.HyperBackscreen.app.ModuleApp
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.bridge.ModulePreferencesSnapshot
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.updater.UpdateCheckResult
import hook.HyperBackscreen.ui.updater.UpdateChecker
import hook.HyperBackscreen.ui.updater.UpdateDialog
import hook.HyperBackscreen.ui.updater.UpdateInfo
import hook.HyperBackscreen.ui.battery.BatteryColorSlot
import hook.HyperBackscreen.batteryring.BatteryPercentPosition
import hook.HyperBackscreen.batteryring.BatteryRingLayout
import hook.HyperBackscreen.ui.config.PreferencesBackup
import hook.HyperBackscreen.ui.util.AppLanguage
import hook.HyperBackscreen.ui.util.RearDisplayCompatibility
import hook.HyperBackscreen.ui.util.RearScreenTimeoutSync
import hook.HyperBackscreen.ui.util.ThemePrefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun RearScreenApp() {
    val context = LocalContext.current
    // 取字符串用配置感知的 LocalResources：切语言 / 深色模式后不会拿到旧配置下的文案。
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val feedback = rememberUiFeedback()
    val retryLabel = stringResource(R.string.common_retry)
    val saveFailed = stringResource(R.string.settings_save_failed)
    val restartSuccess = stringResource(R.string.restart_success)
    val restartFailed = stringResource(R.string.restart_failed)
    val rebootSuccess = stringResource(R.string.restart_device_success)
    val rebootFailed = stringResource(R.string.restart_device_failed)
    val savedPending = stringResource(R.string.settings_saved_pending)

    /**
     * 停止（或重启）作用域进程，让宿主重新读取偏好。
     *
     * 偏好的远程写入在 PrefsBridge 的后台队列里异步提交，杀宿主前先在 IO 线程等它写完，
     * 否则宿主重启后可能读到旧值。[afterPreferenceWrite] 为 true（改设置触发）且模块服务未连接时，
     * 新值只暂存在本机、宿主读不到，重启没有意义，改为提示「已暂存」。
     * [announceSuccess] 为 false 时成功不弹提示（例如恢复配置已经有自己的结果提示）。
     */
    fun refreshScopes(
        vararg packageNames: String,
        allowSystemReboot: Boolean = false,
        afterPreferenceWrite: Boolean = true,
        announceSuccess: Boolean = true,
    ) {
        val reboot = allowSystemReboot && Constants.SYSTEM_PACKAGE in packageNames
        if (afterPreferenceWrite && ModuleApp.getService() == null) {
            feedback.show(savedPending)
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                PrefsBridge.awaitRemoteWrites(RemoteWriteAwaitMillis)
                val commands = scopeActionCommands(packageNames.toList(), allowSystemReboot)
                commands != null && commands.map { executeScopeCommand(it) }.all { it }
            }
            if (ok) {
                if (announceSuccess) feedback.show(if (reboot) rebootSuccess else restartSuccess)
            } else feedback.show(if (reboot) rebootFailed else restartFailed, retryLabel) {
                refreshScopes(
                    *packageNames,
                    allowSystemReboot = allowSystemReboot,
                    afterPreferenceWrite = afterPreferenceWrite,
                )
            }
        }
    }

    // 首帧初始值：一次性从 App 本地缓存读出快照（纯内存 / 本地文件，无 Binder）。
    // 远程偏好与本地缓存的对账由 ModuleApp 绑定服务时的 PrefsBridge.syncOnServiceAvailable 完成，
    // 对账写回本地后会经下面的 observe 监听刷新界面，不再在组合阶段逐项发起约 30 次远程读取。
    val modulePreferences = remember(context) {
        context.getSharedPreferences(Constants.PREF_GROUP, Context.MODE_PRIVATE)
    }
    val initialPrefs = remember(modulePreferences) { ModulePreferencesSnapshot.read(modulePreferences) }
    var enable18ProFeatures by remember { mutableStateOf(initialPrefs.enable18ProFeatures) }
    var saving18ProFeatures by remember { mutableStateOf(false) }
    // 以下三项写入是同步 commit（Binder），放到 IO 线程执行；写入期间忽略重复点击。
    var savingBatteryRing by remember { mutableStateOf(false) }
    var savingLongPress by remember { mutableStateOf(false) }
    var savingAppCard by remember { mutableStateOf(false) }
    var enableBatteryRing by remember { mutableStateOf(initialPrefs.enableBatteryRing) }
    var batteryColorIdle by remember { mutableStateOf(initialPrefs.batteryColorIdle) }
    var batteryColorCharging by remember { mutableStateOf(initialPrefs.batteryColorCharging) }
    var batteryColorLow by remember { mutableStateOf(initialPrefs.batteryColorLow) }
    var batteryPercentPosition by remember {
        mutableStateOf(BatteryPercentPosition.fromPref(initialPrefs.batteryPercentPosition))
    }
    // 位置 / 线条缩放 / 粗细 / 数字字号存成一组字符串偏好，读出来就地收进合法区间。
    // 水平偏移的兜底要跟着位置预设走，所以把当前位置一起传进去。
    var batteryLayout by remember {
        mutableStateOf(batteryLayoutOf(batteryPercentPosition, initialPrefs))
    }
    var autoWallpaperInterval by remember {
        mutableStateOf(AutoWallpaperInterval.fromPref(initialPrefs.autoWallpaperIntervalMinutes))
    }
    var autoWallpaperOrder by remember {
        mutableStateOf(AutoWallpaperOrder.fromPref(initialPrefs.autoWallpaperOrder))
    }
    var rearScreenOffTimeout by remember {
        mutableStateOf(RearScreenOffTimeout.fromPref(initialPrefs.rearScreenOffTimeout))
    }
    var rearScreenSchedule by remember {
        mutableStateOf(
            RearScreenSchedulePrefs(
                enabled = initialPrefs.rearScreenScheduleEnabled,
                start = initialPrefs.rearScreenScheduleStart,
                end = initialPrefs.rearScreenScheduleEnd,
                mode = initialPrefs.rearScreenScheduleMode,
            )
        )
    }
    var disableLongPress by remember { mutableStateOf(initialPrefs.disableLongPress) }
    var removeWallpaperLimit by remember { mutableStateOf(initialPrefs.removeWallpaperLimit) }
    var enableAppCard by remember { mutableStateOf(initialPrefs.enableAppCard) }
    var removeAppCardLimit by remember { mutableStateOf(initialPrefs.removeAppCardLimit) }
    var fixRearScreenApply by remember { mutableStateOf(initialPrefs.fixRearScreenApply) }
    var faceEnrollmentCompat by remember { mutableStateOf(initialPrefs.faceEnrollmentCompat) }
    var keepRearScreenOnInGame by remember { mutableStateOf(initialPrefs.keepRearScreenOnInGame) }
    var floatingNavBar by remember {
        mutableStateOf(PrefsBridge.readFloatingNavBar(context))
    }
    var liquidGlass by remember {
        mutableStateOf(PrefsBridge.readLiquidGlass(context))
    }
    var bottomBarBlur by remember {
        mutableStateOf(PrefsBridge.readBottomBarBlur(context))
    }
    var showFunctionCount by remember {
        mutableStateOf(PrefsBridge.readShowFunctionCount(context))
    }
    var appLanguage by remember {
        mutableStateOf(AppLanguage.current(context))
    }
    var checkUpdates by remember {
        mutableStateOf(PrefsBridge.readCheckUpdates(context))
    }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadFailed by remember { mutableStateOf(false) }
    var disableRearScreenCover by remember { mutableStateOf(initialPrefs.disableRearScreenCover) }
    var themeMode by remember {
        mutableStateOf(ThemePrefs.getThemeMode(context))
    }
    var themeSettingsShortcut by remember { mutableStateOf(initialPrefs.themeSettingsShortcut) }
    var disableDoubleTapWake by remember { mutableStateOf(initialPrefs.disableDoubleTapWake) }
    var doubleTapWakeDisabledPackages by remember {
        mutableStateOf(initialPrefs.doubleTapWakeDisabledPackages)
    }
    // 桌面图标隐藏状态是 PackageManager Binder 调用，挪到 IO 线程读，不放在组合阶段。
    var launcherIconHidden by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        launcherIconHidden = withContext(Dispatchers.IO) { LauncherIconController.isHidden(context) }
    }
    // 服务绑定前是「检测中」；绑定成功即激活。等了 [ActivationBindTimeoutMillis] 仍未绑定才判未激活，
    // 避免冷启动首帧把已激活的模块先显示成红色「未激活」。
    var moduleActivated by remember {
        mutableStateOf(
            if (ModuleApp.getService() != null) ActivationState.ACTIVE else ActivationState.CHECKING
        )
    }
    var activationTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ActivationBindTimeoutMillis)
        activationTimedOut = true
        if (ModuleApp.getService() == null) moduleActivated = ActivationState.INACTIVE
    }
    // Activity 重建（切语言 / 深色模式）后不再重复弹：「本次会话已提示过」存进 saveable 状态。
    var showRearDisplayWarning by rememberSaveable { mutableStateOf(false) }
    var rearDisplayWarningChecked by rememberSaveable { mutableStateOf(false) }
    var startupUpdateChecked by rememberSaveable { mutableStateOf(false) }
    // 恢复配置会覆盖当前设置：先读出备份概要让用户确认，确认后才真正写入。
    var pendingRestore by remember { mutableStateOf<PreferencesBackup.BackupInfo?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (rearDisplayWarningChecked) return@LaunchedEffect
        val hidden = withContext(Dispatchers.IO) {
            RearDisplayCompatibility.isBuiltinPresentationDisabled()
        }
        if (hidden) showRearDisplayWarning = true
        rearDisplayWarningChecked = true
    }

    // 启动时自动检查更新（开关已关则跳过；检查本身有 10 分钟节流）。每个会话只提示一次，
    // 否则关掉弹窗后一重建 Activity，节流期内返回的缓存结果又会把弹窗弹出来。
    LaunchedEffect(Unit) {
        if (checkUpdates && !startupUpdateChecked) {
            val result = UpdateChecker.check()
            if (result is UpdateCheckResult.Available) pendingUpdate = result.info
            // 失败不算「已检查」：下次重建时还可以再试一次。
            if (result != UpdateCheckResult.Failed) startupUpdateChecked = true
        }
    }

    // 拖动取色盘时每帧都会回调：界面状态立即更新，偏好写入按槽位防抖，松手停下后才落盘。
    val pendingColorWrites = remember { mutableMapOf<String, Pair<String, Job>>() }
    DisposableEffect(Unit) {
        onDispose {
            // 页面销毁时把还在防抖中的颜色立即写掉，不丢最后一次修改。
            pendingColorWrites.forEach { (key, pending) ->
                pending.second.cancel()
                PrefsBridge.writeBatteryColorFromUi(context, key, pending.first)
            }
            pendingColorWrites.clear()
        }
    }

    // Provider/UI writes and service reconciliation all update this App-process cache.
    // Read it on the UI thread when handling the event, so queued refreshes cannot replay old values.
    DisposableEffect(context, lifecycleOwner) {
        val preferences = modulePreferences
        var disposed = false
        val listener = Runnable {
            scope.launch {
                if (disposed) return@launch
                val values = ModulePreferencesSnapshot.read(preferences)
                enable18ProFeatures = values.enable18ProFeatures
                enableBatteryRing = values.enableBatteryRing
                batteryColorIdle = values.batteryColorIdle
                batteryColorCharging = values.batteryColorCharging
                batteryColorLow = values.batteryColorLow
                batteryPercentPosition = BatteryPercentPosition.fromPref(values.batteryPercentPosition)
                batteryLayout = batteryLayoutOf(batteryPercentPosition, values)
                autoWallpaperInterval = AutoWallpaperInterval.fromPref(values.autoWallpaperIntervalMinutes)
                autoWallpaperOrder = AutoWallpaperOrder.fromPref(values.autoWallpaperOrder)
                rearScreenOffTimeout = RearScreenOffTimeout.fromPref(values.rearScreenOffTimeout)
                rearScreenSchedule = RearScreenSchedulePrefs(
                    enabled = values.rearScreenScheduleEnabled,
                    start = values.rearScreenScheduleStart,
                    end = values.rearScreenScheduleEnd,
                    mode = values.rearScreenScheduleMode,
                )
                disableLongPress = values.disableLongPress
                removeWallpaperLimit = values.removeWallpaperLimit
                enableAppCard = values.enableAppCard
                removeAppCardLimit = values.removeAppCardLimit
                fixRearScreenApply = values.fixRearScreenApply
                faceEnrollmentCompat = values.faceEnrollmentCompat
                keepRearScreenOnInGame = values.keepRearScreenOnInGame
                themeSettingsShortcut = values.themeSettingsShortcut
                disableRearScreenCover = values.disableRearScreenCover
                disableDoubleTapWake = values.disableDoubleTapWake
                doubleTapWakeDisabledPackages = values.doubleTapWakeDisabledPackages
                moduleActivated = when {
                    ModuleApp.getService() != null -> ActivationState.ACTIVE
                    // 服务断开（之前已激活）或等待超时后仍没有服务，才算未激活。
                    moduleActivated == ActivationState.ACTIVE || activationTimedOut -> ActivationState.INACTIVE
                    else -> ActivationState.CHECKING
                }
            }
        }
        val subscription = ModulePreferencesSnapshot.observe(preferences, listener)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) listener.run()
        }
        ModuleApp.addServiceListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        listener.run()
        onDispose {
            disposed = true
            subscription.close()
            ModuleApp.removeServiceListener(listener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    val isDark = themeMode.resolve(isSystemInDarkTheme())

    DisposableEffect(isDark) {
        val activity = context as? ComponentActivity
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            ) { isDark },
            navigationBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            ) { isDark },
        )
        activity?.window?.isNavigationBarContrastEnforced = false
        onDispose {}
    }

    LaunchedEffect(isDark) {
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    val appColors = if (themeMode.usesDynamicColors) {
        platformDynamicColors(isDark)
    } else if (isDark) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }

    /**
     * 恢复配置只写了偏好；按「恢复前后有变化的键」补上与逐项切换时相同的副作用，
     * 让宿主和系统设置跟着生效：息屏时间同步系统设置、发同步广播、补应用卡片安装请求、重启相关作用域。
     */
    fun applyRestoreSideEffects(before: ModulePreferencesSnapshot, after: ModulePreferencesSnapshot) {
        if (after.rearScreenOffTimeout != before.rearScreenOffTimeout) {
            RearScreenTimeoutSync.apply(RearScreenOffTimeout.fromPref(after.rearScreenOffTimeout))
        }
        if (after.enableBatteryRing != before.enableBatteryRing) {
            sendBatteryRingSync(context, Constants.KEY_ENABLE_BATTERY_RING, after.enableBatteryRing)
        }
        if (after.disableLongPress != before.disableLongPress) {
            sendBatteryRingSync(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, after.disableLongPress)
        }
        if (after.autoWallpaperIntervalMinutes != before.autoWallpaperIntervalMinutes) {
            sendAutoWallpaperSync(context, AutoWallpaperInterval.fromPref(after.autoWallpaperIntervalMinutes))
        }
        val scopes = linkedSetOf<String>()
        if (after.enable18ProFeatures != before.enable18ProFeatures) {
            scopes += listOf(
                Constants.TARGET_PACKAGE, Constants.THEME_STORE_PACKAGE, Constants.PERSONAL_ASSISTANT_PACKAGE
            )
        }
        if (after.removeWallpaperLimit != before.removeWallpaperLimit) scopes += Constants.THEME_STORE_PACKAGE
        if (after.removeAppCardLimit != before.removeAppCardLimit) scopes += Constants.TARGET_PACKAGE
        if (after.fixRearScreenApply != before.fixRearScreenApply) {
            scopes += listOf(Constants.TARGET_PACKAGE, Constants.THEME_STORE_PACKAGE)
        }
        if (after.faceEnrollmentCompat != before.faceEnrollmentCompat) scopes += Constants.THEME_STORE_PACKAGE
        if (after.themeSettingsShortcut != before.themeSettingsShortcut) scopes += Constants.THEME_STORE_PACKAGE
        if (after.enableAppCard != before.enableAppCard) scopes += Constants.TARGET_PACKAGE
        val installAppCard = after.enableAppCard && !before.enableAppCard
        if (scopes.isEmpty() && !installAppCard) return
        scope.launch {
            // 备份把卡片开关从关恢复成开：走与手动开启相同的安装请求流程，宿主才会真的插入卡片。
            if (installAppCard) {
                withContext(Dispatchers.IO) { PrefsBridge.requestInstallAppCardFromUi(context) }
            }
            if (scopes.isNotEmpty()) refreshScopes(*scopes.toTypedArray(), announceSuccess = false)
        }
    }

    fun performRestore() {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val before = ModulePreferencesSnapshot.read(modulePreferences)
                val result = PreferencesBackup.restore(context)
                Triple(result, before, ModulePreferencesSnapshot.read(modulePreferences))
            }
            val (result, before, after) = outcome
            if (result == null) {
                feedback.show(resources.getString(R.string.config_restore_failed))
                return@launch
            }
            // 外观类偏好不在 ModulePreferencesSnapshot 的观察集合里，手动回读一次；
            // 其余模块偏好经 observe 监听自动刷新界面。
            floatingNavBar = PrefsBridge.readFloatingNavBar(context)
            liquidGlass = PrefsBridge.readLiquidGlass(context)
            bottomBarBlur = PrefsBridge.readBottomBarBlur(context)
            showFunctionCount = PrefsBridge.readShowFunctionCount(context)
            checkUpdates = PrefsBridge.readCheckUpdates(context)
            feedback.show(
                resources.getString(
                    R.string.config_restore_done,
                    result.keyCount,
                    result.exportedAt ?: "-",
                )
            )
            applyRestoreSideEffects(before, after)
        }
    }
    MiuixTheme(colors = appColors) {
      val projectColors = remember(appColors, themeMode.usesDynamicColors) {
          appColors(appColors, themeMode.usesDynamicColors)
      }
      CompositionLocalProvider(
          LocalUiFeedback provides feedback,
          LocalAppColors provides projectColors
      ) {
        HomeScreen(
            enableBatteryRing = enableBatteryRing,
            onEnableBatteryRingChange = { value ->
                if (!savingBatteryRing) {
                    savingBatteryRing = true
                    enableBatteryRing = value
                    scope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) {
                                PrefsBridge.writeEnableBatteryRingFromUi(context, value)
                            }
                            // 远程提交失败时新值已作为待同步值留在本机，重连后会补交生效：
                            // 界面保持新值，提示「已暂存」而不是「保存失败」，两边保持一致。
                            if (saved) sendBatteryRingSync(context, Constants.KEY_ENABLE_BATTERY_RING, value)
                            else feedback.show(savedPending)
                        } finally {
                            savingBatteryRing = false
                        }
                    }
                }
            },
            enable18ProFeatures = enable18ProFeatures,
            onEnable18ProFeaturesChange = { newValue ->
                if (!saving18ProFeatures) {
                    saving18ProFeatures = true
                    scope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) {
                                PrefsBridge.writeEnable18ProFeaturesFromUi(context, newValue)
                            }
                            if (saved) {
                                enable18ProFeatures = newValue
                                refreshScopes(Constants.TARGET_PACKAGE, Constants.THEME_STORE_PACKAGE,
                                    Constants.PERSONAL_ASSISTANT_PACKAGE)
                            } else {
                                feedback.show(saveFailed)
                            }
                        } finally {
                            saving18ProFeatures = false
                        }
                    }
                }
            },
            disableLongPress = disableLongPress,
            removeWallpaperLimit = removeWallpaperLimit,
            removeAppCardLimit = removeAppCardLimit,
            autoWallpaperInterval = autoWallpaperInterval,
            autoWallpaperOrder = autoWallpaperOrder,
            rearScreenOffTimeout = rearScreenOffTimeout,
            rearScreenSchedule = rearScreenSchedule,
            keepRearScreenOnInGame = keepRearScreenOnInGame,
            fixRearScreenApply = fixRearScreenApply,
            faceEnrollmentCompat = faceEnrollmentCompat,
            floatingNavBar = floatingNavBar,
            liquidGlass = liquidGlass,
            bottomBarBlur = bottomBarBlur,
            showFunctionCount = showFunctionCount,
            appLanguage = appLanguage,
            checkUpdates = checkUpdates,
            enableAppCard = enableAppCard,
            batteryColorIdle = batteryColorIdle,
            batteryColorCharging = batteryColorCharging,
            batteryColorLow = batteryColorLow,
            batteryPercentPosition = batteryPercentPosition,
            batteryLayout = batteryLayout,
            disableRearScreenCover = disableRearScreenCover,
            disableDoubleTapWake = disableDoubleTapWake,
            doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
            launcherIconHidden = launcherIconHidden,
            moduleActivated = moduleActivated,
            onDisableLongPressChange = { newValue ->
                if (!savingLongPress) {
                    savingLongPress = true
                    disableLongPress = newValue
                    scope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) {
                                PrefsBridge.writeDisableLongPressFromUi(context, newValue)
                            }
                            if (saved) sendBatteryRingSync(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, newValue)
                            else feedback.show(savedPending)
                        } finally {
                            savingLongPress = false
                        }
                    }
                }
            },
            onRemoveWallpaperLimitChange = { newValue ->
                removeWallpaperLimit = newValue
                PrefsBridge.writeRemoveWallpaperLimitFromUi(context, newValue)
                refreshScopes(Constants.THEME_STORE_PACKAGE)
            },
            onRemoveAppCardLimitChange = { newValue ->
                removeAppCardLimit = newValue
                PrefsBridge.writeRemoveAppCardLimitFromUi(context, newValue)
                refreshScopes(Constants.TARGET_PACKAGE)
            },
            onAutoWallpaperIntervalChange = { interval ->
                autoWallpaperInterval = interval
                PrefsBridge.writeAutoWallpaperIntervalFromUi(context, interval.prefValue)
                sendAutoWallpaperSync(context, interval)
            },
            onAutoWallpaperOrderChange = { order ->
                autoWallpaperOrder = order
                // 宿主的调度器每次 tick 都会现读这个键，不依赖广播，改完下一轮就按新顺序来。
                PrefsBridge.writeAutoWallpaperOrderFromUi(context, order.prefValue)
            },
            onRearScreenOffTimeoutChange = { timeout ->
                rearScreenOffTimeout = timeout
                PrefsBridge.writeRearScreenOffTimeoutFromUi(context, timeout.prefValue)
                RearScreenTimeoutSync.apply(timeout)
            },
            onRearScreenScheduleChange = { schedule ->
                rearScreenSchedule = schedule
                PrefsBridge.writeRearScreenScheduleEnabledFromUi(context, schedule.enabled)
                PrefsBridge.writeRearScreenScheduleStartFromUi(context, schedule.start)
                PrefsBridge.writeRearScreenScheduleEndFromUi(context, schedule.end)
                PrefsBridge.writeRearScreenScheduleModeFromUi(context, schedule.mode)
            },
            onFixRearScreenApplyChange = { newValue ->
                fixRearScreenApply = newValue
                PrefsBridge.writeFixRearScreenApplyFromUi(context, newValue)
                refreshScopes(Constants.TARGET_PACKAGE, Constants.THEME_STORE_PACKAGE)
            },
            onFaceEnrollmentCompatChange = { newValue ->
                faceEnrollmentCompat = newValue
                PrefsBridge.writeFaceEnrollmentCompatFromUi(context, newValue)
                refreshScopes(Constants.THEME_STORE_PACKAGE)
            },
            onKeepRearScreenOnInGameChange = { newValue ->
                // 钩子在 system_server（android 作用域），无法单独重启作用域，改完需重启手机。
                keepRearScreenOnInGame = newValue
                PrefsBridge.writeKeepRearScreenOnInGameFromUi(context, newValue)
            },
            onFloatingNavBarChange = { newValue ->
                floatingNavBar = newValue
                PrefsBridge.writeFloatingNavBar(context, newValue)
            },
            onLiquidGlassChange = { newValue ->
                liquidGlass = newValue
                PrefsBridge.writeLiquidGlass(context, newValue)
            },
            onBottomBarBlurChange = { newValue ->
                bottomBarBlur = newValue
                PrefsBridge.writeBottomBarBlur(context, newValue)
            },
            onShowFunctionCountChange = { newValue ->
                showFunctionCount = newValue
                PrefsBridge.writeShowFunctionCount(context, newValue)
            },
            onAppLanguageChange = { newValue ->
                appLanguage = newValue
                AppLanguage.apply(context, newValue)
            },
            onCheckUpdatesChange = { newValue ->
                checkUpdates = newValue
                PrefsBridge.writeCheckUpdates(context, newValue)
            },
            onEnableAppCardChange = { newValue ->
                if (!savingAppCard) {
                    savingAppCard = true
                    scope.launch {
                        try {
                            if (newValue) {
                                val saved = withContext(Dispatchers.IO) {
                                    PrefsBridge.requestInstallAppCardFromUi(context)
                                }
                                // 未提交成功时安装请求已作为待同步值留在本机，重连后会自动执行；
                                // 界面跟着显示为开启并提示「已暂存」，不再显示失败却悄悄生效。
                                enableAppCard = true
                                if (saved) refreshScopes(Constants.TARGET_PACKAGE)
                                else feedback.show(savedPending)
                            } else {
                                enableAppCard = false
                                withContext(Dispatchers.IO) {
                                    PrefsBridge.writeEnableAppCardFromUi(context, false)
                                }
                                refreshScopes(Constants.TARGET_PACKAGE)
                            }
                        } finally {
                            savingAppCard = false
                        }
                    }
                }
            },
            onBatteryColorChange = { slot, value ->
                val key = when (slot) {
                    BatteryColorSlot.IDLE -> Constants.KEY_BATTERY_COLOR_IDLE
                    BatteryColorSlot.CHARGING -> Constants.KEY_BATTERY_COLOR_CHARGING
                    BatteryColorSlot.LOW -> Constants.KEY_BATTERY_COLOR_LOW
                }
                when (slot) {
                    BatteryColorSlot.IDLE -> batteryColorIdle = value
                    BatteryColorSlot.CHARGING -> batteryColorCharging = value
                    BatteryColorSlot.LOW -> batteryColorLow = value
                }
                // 防抖：拖动过程中只更新界面，停手 ColorWriteDebounceMillis 后才写偏好，
                // 避免每帧一次跨进程写入、各作用域每帧收到变更通知。
                pendingColorWrites.remove(key)?.second?.cancel()
                val job = scope.launch {
                    delay(ColorWriteDebounceMillis)
                    pendingColorWrites.remove(key)
                    PrefsBridge.writeBatteryColorFromUi(context, key, value)
                }
                pendingColorWrites[key] = value to job
            },
            onBatteryPercentPositionChange = { position ->
                batteryPercentPosition = position
                // 切位置时立刻按新位置重读一次布局：水平偏移左右各存一份，切过去要落到该侧
                // 自己上次调好的值，不能等偏好回读（会先闪一下旧值）。
                batteryLayout = batteryLayoutOf(position, ModulePreferencesSnapshot.read(modulePreferences))
                PrefsBridge.writeBatteryPercentPositionFromUi(context, position.prefValue)
            },
            onBatteryLayoutChange = { layout ->
                batteryLayout = layout
                // 一项一条偏好；键清单由布局自己给出（水平偏移按当前所在侧取键），加项时不用改这里。
                layout.entries(batteryPercentPosition).forEach { (key, value) ->
                    PrefsBridge.writeBatteryLayoutFromUi(context, key, value)
                }
            },
            onDisableRearScreenCoverChange = { newValue ->
                disableRearScreenCover = newValue
                PrefsBridge.writeDisableRearScreenCoverFromUi(context, newValue)
            },
            onDoubleTapWakeDisabledPackagesChange = { newValue ->
                doubleTapWakeDisabledPackages = newValue
                PrefsBridge.writeDoubleTapWakeDisabledPackagesFromUi(context, newValue)
                // 名单非空即启用，清空名单自动关闭。
                val enabled = PackageListCodec.parse(newValue).isNotEmpty()
                if (disableDoubleTapWake != enabled) {
                    disableDoubleTapWake = enabled
                    PrefsBridge.writeDisableDoubleTapWakeFromUi(context, enabled)
                }
            },
            onLauncherIconHiddenChange = { newValue ->
                if (LauncherIconController.setHidden(context, newValue)) {
                    launcherIconHidden = newValue
                } else {
                    feedback.show(saveFailed, retryLabel) {
                        if (LauncherIconController.setHidden(context, newValue)) launcherIconHidden = newValue
                        else feedback.show(saveFailed)
                    }
                }
            },
            onRestartScopes = { selected ->
                // 手动重启：不依赖刚改的设置，服务未连接也照常执行。
                refreshScopes(*selected.toTypedArray(), allowSystemReboot = true, afterPreferenceWrite = false)
            },
            themeMode = themeMode,
            onThemeModeChange = { newMode ->
                ThemePrefs.setThemeMode(context, newMode)
                themeMode = newMode
            },
            themeSettingsShortcut = themeSettingsShortcut,
            onThemeSettingsShortcutChange = { enabled ->
                themeSettingsShortcut = enabled
                PrefsBridge.writeThemeSettingsShortcutFromUi(context, enabled)
                refreshScopes(Constants.THEME_STORE_PACKAGE)
            },
            onBackupPreferences = {
                scope.launch(Dispatchers.IO) {
                    val result = PreferencesBackup.backup(context)
                    withContext(Dispatchers.Main) {
                        feedback.show(
                            when {
                                result == null -> resources.getString(R.string.config_backup_failed)
                                result.previousExportedAt != null -> resources.getString(
                                    R.string.config_backup_done_overwrite,
                                    result.keyCount,
                                    result.previousExportedAt,
                                )
                                else -> resources.getString(
                                    R.string.config_backup_done,
                                    result.keyCount,
                                    result.path,
                                )
                            }
                        )
                    }
                }
            },
            onRestorePreferences = {
                scope.launch {
                    val info = withContext(Dispatchers.IO) { PreferencesBackup.inspect() }
                    if (info == null) {
                        feedback.show(resources.getString(R.string.config_restore_failed))
                    } else {
                        pendingRestore = info
                        showRestoreConfirm = true
                    }
                }
            }
        )

      }
        UpdateDialog(
            update = pendingUpdate,
            downloadFailed = downloadFailed,
            onDownload = {
                pendingUpdate?.let {
                    downloadFailed = !UpdateChecker.openDownload(context, it)
                    if (!downloadFailed) pendingUpdate = null
                }
            },
            onDismiss = { pendingUpdate = null; downloadFailed = false }
        )

        WindowDialog(
            show = showRearDisplayWarning,
            title = stringResource(R.string.compat_rear_display_hidden_title),
            onDismissRequest = { showRearDisplayWarning = false }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.compat_rear_display_hidden_message),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
                DialogButtonRow(
                    primaryText = stringResource(R.string.common_confirm),
                    onPrimary = { showRearDisplayWarning = false }
                )
            }
        }

        WindowDialog(
            show = showRestoreConfirm,
            title = stringResource(R.string.config_restore_confirm_title),
            summary = pendingRestore?.let { info ->
                stringResource(
                    R.string.config_restore_confirm_summary,
                    info.exportedAt ?: "-",
                    info.keyCount,
                    info.appVersion ?: "-",
                )
            },
            onDismissRequest = { showRestoreConfirm = false },
            onDismissFinished = { pendingRestore = null }
        ) {
            DialogButtonRow(
                secondaryText = stringResource(R.string.common_cancel),
                onSecondary = { showRestoreConfirm = false },
                primaryText = stringResource(R.string.config_restore_confirm_action),
                onPrimary = {
                    showRestoreConfirm = false
                    performRestore()
                }
            )
        }
    }
}

private fun executeScopeCommand(command: Array<String>): Boolean {
    return try {
        val process = Runtime.getRuntime().exec(command)
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }
}

/** 从偏好快照组装电量环布局；水平偏移的兜底跟着 [position] 走。 */
private fun batteryLayoutOf(
    position: BatteryPercentPosition,
    values: ModulePreferencesSnapshot,
): BatteryRingLayout = BatteryRingLayout.of(
    position,
    mapOf(
        Constants.KEY_BATTERY_PERCENT_OFFSET_X to values.batteryPercentOffsetX,
        Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT to values.batteryPercentOffsetXLeft,
        Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT to values.batteryPercentOffsetXRight,
        Constants.KEY_BATTERY_PERCENT_OFFSET_Y to values.batteryPercentOffsetY,
        Constants.KEY_BATTERY_RING_SCALE to values.batteryRingScale,
        Constants.KEY_BATTERY_RING_STROKE to values.batteryRingStroke,
        Constants.KEY_BATTERY_PERCENT_TEXT_SIZE to values.batteryPercentTextSize,
        Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to values.batteryPercentTextWeight,
        Constants.KEY_BATTERY_RING_TRACK to values.batteryRingTrack,
        Constants.KEY_BATTERY_RING_STYLE to values.batteryRingStyle,
        Constants.KEY_BATTERY_LOW_THRESHOLD to values.batteryLowThreshold,
        Constants.KEY_BATTERY_CHARGE_SWEEP to values.batteryChargeSweep,
    )::get
)

/** 通知背屏进程某个开关已变（电量环 / 长按编辑共用同一条同步广播）。 */
private fun sendBatteryRingSync(context: Context, key: String, value: Boolean) {
    context.sendBroadcast(
        Intent(Constants.ACTION_SYNC_BATTERY_RING)
            .setPackage(Constants.TARGET_PACKAGE)
            .putExtra(Constants.EXTRA_PREFERENCE_KEY, key)
            .putExtra(Constants.EXTRA_PREFERENCE_VALUE, value)
    )
}

private fun sendAutoWallpaperSync(context: Context, interval: AutoWallpaperInterval) {
    context.sendBroadcast(
        Intent(Constants.ACTION_SYNC_AUTO_WALLPAPER)
            .setPackage(Constants.TARGET_PACKAGE)
            .putExtra(Constants.EXTRA_INTERVAL_MINUTES, interval.minutes)
    )
}

/** 杀宿主前等待远程偏好写完的上限。 */
private const val RemoteWriteAwaitMillis = 3000L

/** 取色盘写偏好的防抖间隔。 */
private const val ColorWriteDebounceMillis = 300L

/** 等 Xposed 服务绑定的最长时间；正常情况下几百毫秒内就绑定好了。 */
private const val ActivationBindTimeoutMillis = 2500L
