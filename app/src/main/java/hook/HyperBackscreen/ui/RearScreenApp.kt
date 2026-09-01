package hook.HyperBackscreen.ui

import android.graphics.Color
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
import hook.HyperBackscreen.R
import hook.HyperBackscreen.app.ModuleApp
import hook.HyperBackscreen.bridge.PrefsBridge
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
internal fun RearScreenApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
    var enableSwipePanel by remember {
        mutableStateOf(PrefsBridge.readEnableSwipePanelForUi(context))
    }
    var disableRearScreenCover by remember {
        mutableStateOf(PrefsBridge.readDisableRearScreenCoverForUi(context))
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

    // Xposed 服务是异步绑定的：首帧组合时可能尚未就绪，读到的是本地/默认值。
    // 服务绑定后通过回调重新读取远程偏好并刷新开关，取代之前每 500ms 一次的空转轮询。
    DisposableEffect(Unit) {
        val listener = Runnable {
            scope.launch {
                val (dlp, rwl, fix) = withContext(Dispatchers.IO) {
                    Triple(
                        PrefsBridge.readDisableLongPressForUi(context),
                        PrefsBridge.readRemoveWallpaperLimitForUi(context),
                        PrefsBridge.readFixRearScreenApplyForUi(context)
                    )
                }
                val swipe = withContext(Dispatchers.IO) {
                    PrefsBridge.readEnableSwipePanelForUi(context)
                }
                val (cover, doubleTap, packages) = withContext(Dispatchers.IO) {
                    Triple(
                        PrefsBridge.readDisableRearScreenCoverForUi(context),
                        PrefsBridge.readDisableDoubleTapWakeForUi(context),
                        PrefsBridge.readDoubleTapWakeDisabledPackagesForUi(context)
                    )
                }
                disableLongPress = dlp
                removeWallpaperLimit = rwl
                fixRearScreenApply = fix
                enableSwipePanel = swipe
                disableRearScreenCover = cover
                disableDoubleTapWake = doubleTap
                doubleTapWakeDisabledPackages = packages
                moduleActivated = ModuleApp.getService() != null
            }
        }
        ModuleApp.addServiceListener(listener)
        if (ModuleApp.getService() != null) listener.run()
        onDispose { ModuleApp.removeServiceListener(listener) }
    }

    val isDark = isSystemInDarkTheme()

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

    MiuixTheme(
        colors = if (isDark) darkColorScheme() else lightColorScheme()
    ) {
        HomeScreen(
            disableLongPress = disableLongPress,
            removeWallpaperLimit = removeWallpaperLimit,
            fixRearScreenApply = fixRearScreenApply,
            floatingNavBar = floatingNavBar,
            liquidGlass = liquidGlass,
            enableSwipePanel = enableSwipePanel,
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
            },
            onFixRearScreenApplyChange = { newValue ->
                fixRearScreenApply = newValue
                PrefsBridge.writeFixRearScreenApplyFromUi(context, newValue)
            },
            onFloatingNavBarChange = { newValue ->
                floatingNavBar = newValue
                PrefsBridge.writeFloatingNavBar(context, newValue)
            },
            onLiquidGlassChange = { newValue ->
                liquidGlass = newValue
                PrefsBridge.writeLiquidGlass(context, newValue)
            },
            onEnableSwipePanelChange = { newValue ->
                enableSwipePanel = newValue
                PrefsBridge.writeEnableSwipePanelFromUi(context, newValue)
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
                }
            },
            onForceStopPackage = { packageName ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { forceStopPackage(packageName) }
                    Toast.makeText(
                        context,
                        context.getString(if (ok) R.string.restart_success else R.string.restart_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

private fun forceStopPackage(packageName: String): Boolean {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
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
