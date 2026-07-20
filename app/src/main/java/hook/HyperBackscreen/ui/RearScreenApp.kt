package hook.HyperBackscreen.ui

import android.app.Activity
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import hook.HyperBackscreen.app.ModuleApp
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.bridge.SettingsSyncBridge
import hook.HyperBackscreen.common.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
internal fun RearScreenApp() {
    val context = LocalContext.current
    var serviceAvailable by remember { mutableStateOf(ModuleApp.getService() != null) }

    LaunchedEffect(Unit) {
        while (true) {
            val available = ModuleApp.getService() != null
            if (available != serviceAvailable) {
                serviceAvailable = available
            }
            delay(500)
        }
    }

    var disableLongPress by remember {
        mutableStateOf(PrefsBridge.readDisableLongPressForUi(context))
    }
    var removeWallpaperLimit by remember {
        mutableStateOf(PrefsBridge.readRemoveWallpaperLimitForUi(context))
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

    val isDark = false

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

    val scope = rememberCoroutineScope()

    MiuixTheme(
        colors = lightColorScheme()
    ) {
        HomeScreen(
            disableLongPress = disableLongPress,
            removeWallpaperLimit = removeWallpaperLimit,
            fixRearScreenApply = fixRearScreenApply,
            floatingNavBar = floatingNavBar,
            liquidGlass = liquidGlass,
            onDisableLongPressChange = { newValue ->
                disableLongPress = newValue
                PrefsBridge.writeDisableLongPressFromUi(context, newValue)
                SettingsSyncBridge.sendBooleanSetting(context, Constants.KEY_DISABLE_LONG_PRESS_EDIT, newValue)
            },
            onRemoveWallpaperLimitChange = { newValue ->
                removeWallpaperLimit = newValue
                PrefsBridge.writeRemoveWallpaperLimitFromUi(context, newValue)
                SettingsSyncBridge.sendBooleanSetting(context, Constants.KEY_REMOVE_WALLPAPER_LIMIT, newValue)
            },
            onFixRearScreenApplyChange = { newValue ->
                fixRearScreenApply = newValue
                PrefsBridge.writeFixRearScreenApplyFromUi(context, newValue)
                SettingsSyncBridge.sendBooleanSetting(context, Constants.KEY_FIX_REAR_SCREEN_APPLY, newValue)
            },
            onFloatingNavBarChange = { newValue ->
                floatingNavBar = newValue
                PrefsBridge.writeFloatingNavBar(context, newValue)
            },
            onLiquidGlassChange = { newValue ->
                liquidGlass = newValue
                PrefsBridge.writeLiquidGlass(context, newValue)
            },
            onForceStopPackage = { packageName ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        forceStopPackage(packageName)
                    }
                }
            }
        )
    }
}

private fun forceStopPackage(packageName: String) {
    try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
        process.waitFor()
    } catch (_: Exception) {
    }
}
