package hook.HyperBackscreen.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.AboutArrowPreference
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.overScrollVertical

private data class LicenseItem(
    val name: String,
    val license: String,
    val url: String
)

private val licenses = listOf(
    LicenseItem(
        name = "MiBackscreen",
        license = "GPL-3.0",
        url = "https://www.gnu.org/licenses/gpl-3.0.html"
    ),
    LicenseItem(
        name = "compose-miuix-ui (miuix library)",
        license = "Apache-2.0",
        url = "https://github.com/compose-miuix-ui/miuix"
    ),
    LicenseItem(
        name = "AndroidX Activity Compose",
        license = "Apache-2.0",
        url = "https://developer.android.com/jetpack/androidx/activity/activity-compose"
    ),
    LicenseItem(
        name = "Modern Xposed API",
        license = "Apache-2.0",
        url = "https://github.com/libxposed/api"
    ),
    LicenseItem(
        name = "AndroidLiquidGlass",
        license = "Apache-2.0",
        url = "https://github.com/Kyant0/AndroidLiquidGlass"
    ),
    LicenseItem(
        name = "KernelSU (FloatingBottomBar)",
        license = "GPL-3.0",
        url = "https://github.com/tiann/KernelSU"
    )
)

@Composable
internal fun LicensePage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.license_title),
                    largeTitle = stringResource(R.string.license_title),
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
                    Spacer(Modifier.height(12.dp))
                }
                items(licenses) { license ->
                    CardBlock {
                        AboutArrowPreference(
                            title = license.name,
                            summary = "${license.license} · ${license.url}",
                            url = license.url
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }
}
