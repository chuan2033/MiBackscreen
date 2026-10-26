package hook.HyperBackscreen.ui

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import hook.HyperBackscreen.ui.updater.UpdateChecker
import hook.HyperBackscreen.ui.updater.UpdateDialog
import hook.HyperBackscreen.ui.updater.UpdateInfo
import hook.HyperBackscreen.ui.battery.BatteryColorSlot
import hook.HyperBackscreen.ui.battery.BatteryPercentPosition
import hook.HyperBackscreen.ui.battery.BatteryRingLayout
import hook.HyperBackscreen.ui.config.PreferencesBackup
import hook.HyperBackscreen.ui.util.AppLanguage
import hook.HyperBackscreen.ui.util.RearDisplayCompatibility
import hook.HyperBackscreen.ui.util.RearScreenTimeoutSync
import hook.HyperBackscreen.ui.util.ThemeMode
import hook.HyperBackscreen.ui.util.ThemePrefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun RearScreenApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val feedback = rememberUiFeedback()
    val retryLabel = stringResource(R.string.common_retry)
    val saveFailed = stringResource(R.string.settings_save_failed)
    val restartSuccess = stringResource(R.string.restart_success)
    val restartFailed = stringResource(R.string.restart_failed)
    val rebootSuccess = stringResource(R.string.restart_device_success)
    val rebootFailed = stringResource(R.string.restart_device_failed)
    fun refreshScopes(vararg packageNames: String, allowSystemReboot: Boolean = false) {
        val reboot = allowSystemReboot && Constants.SYSTEM_PACKAGE in packageNames
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val commands = scopeActionCommands(packageNames.toList(), allowSystemReboot)
                commands != null && commands.map { executeScopeCommand(it) }.all { it }
            }
            if (ok) feedback.show(if (reboot) rebootSuccess else restartSuccess)
            else feedback.show(if (reboot) rebootFailed else restartFailed, retryLabel) {
                refreshScopes(*packageNames, allowSystemReboot = allowSystemReboot)
            }
        }
    }

    var enable18ProFeatures by remember {
        mutableStateOf(PrefsBridge.readEnable18ProFeaturesForUi(context))
    }
    var saving18ProFeatures by remember { mutableStateOf(false) }
    var enableBatteryRing by remember { mutableStateOf(PrefsBridge.readEnableBatteryRingForUi(context)) }
    var batteryColorIdle by remember {
        mutableStateOf(PrefsBridge.readBatteryColorForUi(context, Constants.KEY_BATTERY_COLOR_IDLE))
    }
    var batteryColorCharging by remember {
        mutableStateOf(PrefsBridge.readBatteryColorForUi(context, Constants.KEY_BATTERY_COLOR_CHARGING))
    }
    var batteryColorLow by remember {
        mutableStateOf(PrefsBridge.readBatteryColorForUi(context, Constants.KEY_BATTERY_COLOR_LOW))
    }
    var batteryPercentPosition by remember {
        mutableStateOf(BatteryPercentPosition.fromPref(PrefsBridge.readBatteryPercentPositionForUi(context)))
    }
    // 位置 / 线条缩放 / 粗细 / 数字字号存成一组字符串偏好，读出来就地收进合法区间。
    // 水平偏移的兜底要跟着位置预设走，所以把当前位置一起传进去。
    var batteryLayout by remember {
        mutableStateOf(
            BatteryRingLayout.of(batteryPercentPosition) { key ->
                PrefsBridge.readBatteryLayoutForUi(context, key)
            }
        )
    }
    var autoWallpaperInterval by remember {
        mutableStateOf(AutoWallpaperInterval.fromPref(PrefsBridge.readAutoWallpaperIntervalForUi(context)))
    }
    var rearScreenOffTimeout by remember {
        mutableStateOf(RearScreenOffTimeout.fromPref(PrefsBridge.readRearScreenOffTimeoutForUi(context)))
    }
    var rearScreenSchedule by remember {
        mutableStateOf(
            RearScreenSchedulePrefs(
                enabled = PrefsBridge.readRearScreenScheduleEnabledForUi(context),
                start = PrefsBridge.readRearScreenScheduleStartForUi(context),
                end = PrefsBridge.readRearScreenScheduleEndForUi(context),
                mode = PrefsBridge.readRearScreenScheduleModeForUi(context),
            )
        )
    }
    var disableLongPress by remember {
        mutableStateOf(PrefsBridge.readDisableLongPressForUi(context))
    }
    var removeWallpaperLimit by remember {
        mutableStateOf(PrefsBridge.readRemoveWallpaperLimitForUi(context))
    }
    var enableAppCard by remember {
        mutableStateOf(PrefsBridge.readEnableAppCardForUi(context))
    }
    var removeAppCardLimit by remember {
        mutableStateOf(PrefsBridge.readRemoveAppCardLimitForUi(context))
    }
    var fixRearScreenApply by remember {
        mutableStateOf(PrefsBridge.readFixRearScreenApplyForUi(context))
    }
    var faceEnrollmentCompat by remember {
        mutableStateOf(PrefsBridge.readFaceEnrollmentCompatForUi(context))
    }
    var keepRearScreenOnInGame by remember {
        mutableStateOf(PrefsBridge.readKeepRearScreenOnInGameForUi(context))
    }
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
    var disableRearScreenCover by remember {
        mutableStateOf(PrefsBridge.readDisableRearScreenCoverForUi(context))
    }
    var themeMode by remember {
        mutableStateOf(ThemePrefs.getThemeMode(context))
    }
    var themeSettingsShortcut by remember {
        mutableStateOf(PrefsBridge.readThemeSettingsShortcutForUi(context))
    }
    var disableDoubleTapWake by remember {
        mutableStateOf(PrefsBridge.readDisableDoubleTapWakeForUi(context))
    }
    var doubleTapWakeDisabledPackages by remember {
        mutableStateOf(PrefsBridge.readDoubleTapWakeDisabledPackagesForUi(context))
    }
    var launcherIconHidden by remember {
        mutableStateOf(LauncherIconController.isHidden(context))
    }
    var moduleActivated by remember {
        mutableStateOf(ModuleApp.getService() != null)
    }
    var showRearDisplayWarning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val hidden = withContext(Dispatchers.IO) {
            RearDisplayCompatibility.isBuiltinPresentationDisabled()
        }
        if (hidden) showRearDisplayWarning = true
    }

    // 启动时自动检查更新（开关已关则跳过；检查本身有 10 分钟节流）。
    LaunchedEffect(Unit) {
        if (checkUpdates) {
            val update = UpdateChecker.check()
            if (update != null) pendingUpdate = update
        }
    }

    // Provider/UI writes and service reconciliation all update this App-process cache.
    // Read it on the UI thread when handling the event, so queued refreshes cannot replay old values.
    DisposableEffect(context, lifecycleOwner) {
        val preferences = context.getSharedPreferences(Constants.PREF_GROUP, Context.MODE_PRIVATE)
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
                batteryLayout = BatteryRingLayout.of(
                    batteryPercentPosition,
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
                    )::get
                )
                autoWallpaperInterval = AutoWallpaperInterval.fromPref(values.autoWallpaperIntervalMinutes)
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
                moduleActivated = ModuleApp.getService() != null
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

    MiuixTheme(colors = appColors) {
      CompositionLocalProvider(LocalUiFeedback provides feedback) {
        HomeScreen(
            enableBatteryRing = enableBatteryRing,
            onEnableBatteryRingChange = { value ->
                enableBatteryRing = value
                if (PrefsBridge.writeEnableBatteryRingFromUi(context, value)) {
                    context.sendBroadcast(
                        Intent(Constants.ACTION_SYNC_BATTERY_RING)
                            .setPackage(Constants.TARGET_PACKAGE)
                            .putExtra(Constants.EXTRA_PREFERENCE_KEY, Constants.KEY_ENABLE_BATTERY_RING)
                            .putExtra(Constants.EXTRA_PREFERENCE_VALUE, value)
                    )
                } else {
                    feedback.show(saveFailed)
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
                disableLongPress = newValue
                if (PrefsBridge.writeDisableLongPressFromUi(context, newValue)) {
                    context.sendBroadcast(
                        Intent(Constants.ACTION_SYNC_BATTERY_RING)
                            .setPackage(Constants.TARGET_PACKAGE)
                            .putExtra(Constants.EXTRA_PREFERENCE_KEY, Constants.KEY_DISABLE_LONG_PRESS_EDIT)
                            .putExtra(Constants.EXTRA_PREFERENCE_VALUE, newValue)
                    )
                } else {
                    feedback.show(saveFailed)
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
                context.sendBroadcast(
                    Intent(Constants.ACTION_SYNC_AUTO_WALLPAPER)
                        .setPackage(Constants.TARGET_PACKAGE)
                        .putExtra(Constants.EXTRA_INTERVAL_MINUTES, interval.minutes)
                )
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
                if (newValue) {
                    val saved = PrefsBridge.requestInstallAppCardFromUi(context)
                    if (saved) {
                        enableAppCard = true
                        refreshScopes(Constants.TARGET_PACKAGE)
                    } else {
                        feedback.show(saveFailed)
                    }
                } else {
                    enableAppCard = false
                    PrefsBridge.writeEnableAppCardFromUi(context, false)
                    refreshScopes(Constants.TARGET_PACKAGE)
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
                PrefsBridge.writeBatteryColorFromUi(context, key, value)
            },
            onBatteryPercentPositionChange = { position ->
                batteryPercentPosition = position
                // 切位置时立刻按新位置重读一次布局：水平偏移左右各存一份，切过去要落到该侧
                // 自己上次调好的值，不能等偏好回读（会先闪一下旧值）。
                batteryLayout = BatteryRingLayout.of(position) { key ->
                    PrefsBridge.readBatteryLayoutForUi(context, key)
                }
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
            onRestartScopes = { selected -> refreshScopes(*selected.toTypedArray(), allowSystemReboot = true) },
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
                                result == null -> context.getString(R.string.config_backup_failed)
                                result.previousExportedAt != null -> context.getString(
                                    R.string.config_backup_done_overwrite,
                                    result.keyCount,
                                    result.previousExportedAt,
                                )
                                else -> context.getString(
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
                scope.launch(Dispatchers.IO) {
                    val result = PreferencesBackup.restore(context)
                    withContext(Dispatchers.Main) {
                        if (result == null) {
                            feedback.show(context.getString(R.string.config_restore_failed))
                        } else {
                            // 外观类偏好不在 ModulePreferencesSnapshot 的观察集合里，手动回读一次；
                            // 背屏电量等参数在挂载时读取，提示用户重进背屏桌面。
                            floatingNavBar = PrefsBridge.readFloatingNavBar(context)
                            liquidGlass = PrefsBridge.readLiquidGlass(context)
                            bottomBarBlur = PrefsBridge.readBottomBarBlur(context)
                            showFunctionCount = PrefsBridge.readShowFunctionCount(context)
                            checkUpdates = PrefsBridge.readCheckUpdates(context)
                            themeSettingsShortcut =
                                PrefsBridge.readThemeSettingsShortcutForUi(context)
                            feedback.show(
                                context.getString(
                                    R.string.config_restore_done,
                                    result.keyCount,
                                    result.exportedAt ?: "-",
                                )
                            )
                        }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        onClick = { showRearDisplayWarning = false },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        content = { Text(stringResource(R.string.common_confirm)) }
                    )
                }
            }
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
