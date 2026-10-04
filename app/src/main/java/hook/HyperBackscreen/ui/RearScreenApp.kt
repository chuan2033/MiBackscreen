package hook.HyperBackscreen.ui

import android.graphics.Color
import android.content.Context
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
import hook.HyperBackscreen.ui.updater.UpdateChecker
import hook.HyperBackscreen.ui.updater.UpdateDialog
import hook.HyperBackscreen.ui.updater.UpdateInfo
import hook.HyperBackscreen.ui.util.AppLanguage
import hook.HyperBackscreen.ui.util.RearDisplayCompatibility
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
    var enablePickup by remember {
        mutableStateOf(PrefsBridge.readEnablePickupForUi(context))
    }
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
                disableLongPress = values.disableLongPress
                removeWallpaperLimit = values.removeWallpaperLimit
                enableAppCard = values.enableAppCard
                removeAppCardLimit = values.removeAppCardLimit
                fixRearScreenApply = values.fixRearScreenApply
                enablePickup = values.enablePickup
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
            disableLongPress = disableLongPress,
            removeWallpaperLimit = removeWallpaperLimit,
            removeAppCardLimit = removeAppCardLimit,
            fixRearScreenApply = fixRearScreenApply,
            floatingNavBar = floatingNavBar,
            liquidGlass = liquidGlass,
            bottomBarBlur = bottomBarBlur,
            showFunctionCount = showFunctionCount,
            appLanguage = appLanguage,
            checkUpdates = checkUpdates,
            enableAppCard = enableAppCard,
            enablePickup = enablePickup,
            disableRearScreenCover = disableRearScreenCover,
            disableDoubleTapWake = disableDoubleTapWake,
            doubleTapWakeDisabledPackages = doubleTapWakeDisabledPackages,
            launcherIconHidden = launcherIconHidden,
            moduleActivated = moduleActivated,
            onDisableLongPressChange = { newValue ->
                disableLongPress = newValue
                PrefsBridge.writeDisableLongPressFromUi(context, newValue)
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
            onFixRearScreenApplyChange = { newValue ->
                fixRearScreenApply = newValue
                PrefsBridge.writeFixRearScreenApplyFromUi(context, newValue)
                refreshScopes(Constants.TARGET_PACKAGE, Constants.THEME_STORE_PACKAGE)
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
                enableAppCard = newValue
                PrefsBridge.writeEnableAppCardFromUi(context, newValue)
                refreshScopes(Constants.TARGET_PACKAGE)
            },
            onEnablePickupChange = { newValue ->
                enablePickup = newValue
                PrefsBridge.writeEnablePickupFromUi(context, newValue)
            },
            onDisableRearScreenCoverChange = { newValue ->
                disableRearScreenCover = newValue
                PrefsBridge.writeDisableRearScreenCoverFromUi(context, newValue)
            },
            onDisableDoubleTapWakeChange = { newValue ->
                disableDoubleTapWake = newValue
                PrefsBridge.writeDisableDoubleTapWakeFromUi(context, newValue)
            },
            onDoubleTapWakeDisabledPackagesChange = { newValue ->
                doubleTapWakeDisabledPackages = newValue
                PrefsBridge.writeDoubleTapWakeDisabledPackagesFromUi(context, newValue)
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
