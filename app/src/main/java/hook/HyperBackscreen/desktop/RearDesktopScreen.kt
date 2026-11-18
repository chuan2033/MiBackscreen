package hook.HyperBackscreen.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 背屏桌面上的一个应用入口。 */
internal data class DesktopApp(val component: ComponentName, val label: String)

/** 背屏固定用深色：OLED 背屏上黑底更省电，也和背屏中心的观感一致。 */
@Composable
internal fun RearDesktopScreen(
    onLaunch: (ComponentName) -> Unit,
    onExit: () -> Unit,
) {
    MiuixTheme(colors = darkColorScheme()) {
        val context = LocalContext.current
        val apps by produceState<List<DesktopApp>?>(initialValue = null) {
            value = withContext(Dispatchers.IO) { loadApps(context) }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            DesktopHeader(onExit = onExit)
            Spacer(Modifier.size(6.dp))
            val list = apps
            when {
                list == null -> Unit
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(R.string.rear_desktop_empty), color = Color.White.copy(alpha = 0.6f))
                }
                else -> AppGrid(list, onLaunch)
            }
        }
    }
}

@Composable
private fun DesktopHeader(onExit: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // 对齐到下一分钟再刷新。
            delay(60_000L - now % 60_000L)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) {
        val locale = Locale.getDefault()
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEE"), locale).format(Date(now))
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = time, color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.size(10.dp))
        Text(text = date, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onExit),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rear_desktop_exit),
                contentDescription = stringResource(R.string.rear_desktop_exit),
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AppGrid(apps: List<DesktopApp>, onLaunch: (ComponentName) -> Unit) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    val cache = remember(iconPx) { LruCache<ComponentName, ImageBitmap>(ICON_CACHE_SIZE) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 68.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(apps, key = { it.component.flattenToString() }) { app ->
            val icon by produceState(initialValue = cache.get(app.component), app.component, iconPx) {
                if (value == null) {
                    value = withContext(Dispatchers.IO) { loadIcon(context, app.component, iconPx) }
                        ?.also { cache.put(app.component, it) }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onLaunch(app.component) }
                    .padding(vertical = 6.dp, horizontal = 2.dp)
            ) {
                val bitmap = icon
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
                } else {
                    Box(Modifier.size(ICON_SIZE).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)))
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = app.label,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 主屏桌面上能看到的应用（带 LAUNCHER 入口），去掉模块自己与背屏中心，按名称排序。 */
private fun loadApps(context: Context): List<DesktopApp> {
    val packageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(Locale.getDefault())
    return runCatching { packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)) }
        .getOrDefault(emptyList())
        .asSequence()
        .mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (activity.packageName == Constants.MODULE_PACKAGE || activity.packageName == Constants.TARGET_PACKAGE) {
                return@mapNotNull null
            }
            DesktopApp(
                component = ComponentName(activity.packageName, activity.name),
                label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(activity.packageName),
            )
        }
        .distinctBy { it.component }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
        .toList()
}

private fun loadIcon(context: Context, component: ComponentName, sizePx: Int): ImageBitmap? = runCatching {
    context.packageManager.getActivityIcon(component).toBitmap(sizePx, sizePx).asImageBitmap()
}.getOrNull()

private val ICON_SIZE = 44.dp
private const val ICON_CACHE_SIZE = 120
