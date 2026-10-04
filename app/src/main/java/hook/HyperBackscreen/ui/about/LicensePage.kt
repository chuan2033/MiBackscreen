package hook.HyperBackscreen.ui.about

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

// Reviewed against releaseRuntimeClasspath and local source attribution headers.
// Group artifacts from the same project; Gradle/compiler/test tools are not app dependencies.
private val licenses = listOf(
    LicenseItem(
        name = "Miuix (UI / Preference / Icons / Blur / Navigation)",
        license = "Apache-2.0",
        url = "https://github.com/compose-miuix-ui/miuix"
    ),
    LicenseItem(
        // Includes Compose, Activity, Lifecycle, Core, SavedState, Collection, Window,
        // NavigationEvent, Graphics, Emoji2 and their supporting AndroidX modules.
        name = "AndroidX / Jetpack Compose",
        license = "Apache-2.0",
        url = "https://github.com/androidx/androidx"
    ),
    LicenseItem(
        name = "Compose Multiplatform",
        license = "Apache-2.0",
        url = "https://github.com/JetBrains/compose-multiplatform"
    ),
    LicenseItem(
        name = "JetBrains AndroidX (Lifecycle / SavedState)",
        license = "Apache-2.0",
        url = "https://github.com/JetBrains/compose-multiplatform-core"
    ),
    LicenseItem(
        name = "Kotlin Standard Library",
        license = "Apache-2.0",
        url = "https://github.com/JetBrains/kotlin"
    ),
    LicenseItem(
        name = "kotlinx.coroutines",
        license = "Apache-2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    LicenseItem(
        name = "kotlinx.serialization",
        license = "Apache-2.0",
        url = "https://github.com/Kotlin/kotlinx.serialization"
    ),
    LicenseItem(
        name = "kotlinx.collections.immutable",
        license = "Apache-2.0",
        url = "https://github.com/Kotlin/kotlinx.collections.immutable"
    ),
    LicenseItem(
        name = "Material Color Utilities (Google / MaterialKolor)",
        // The utilities module has its own Apache-2.0 LICENSE. MaterialKolor's
        // root MIT license / published POM does not replace that module license.
        license = "Apache-2.0",
        url = "https://github.com/jordond/MaterialKolor#license"
    ),
    LicenseItem(
        name = "Poko Annotations",
        license = "Apache-2.0",
        url = "https://github.com/drewhamilton/Poko"
    ),
    LicenseItem(
        name = "JetBrains Java Annotations",
        license = "Apache-2.0",
        url = "https://github.com/JetBrains/java-annotations"
    ),
    LicenseItem(
        name = "JSpecify",
        license = "Apache-2.0",
        url = "https://github.com/jspecify/jspecify"
    ),
    LicenseItem(
        name = "Guava ListenableFuture",
        license = "Apache-2.0",
        url = "https://github.com/google/guava"
    ),
    LicenseItem(
        name = "Modern Xposed API",
        license = "Apache-2.0",
        // compileOnly: implemented by the Xposed framework, not bundled as API classes.
        url = "https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0"
    ),
    LicenseItem(
        name = "libxposed Service / Interface",
        license = "Apache-2.0",
        // Local JARs match classes.jar in the official 102.0.0 Maven AARs.
        url = "https://central.sonatype.com/artifact/io.github.libxposed/service/102.0.0"
    ),
    LicenseItem(
        name = "AndroidLiquidGlass (Backdrop / Liquid Glass)",
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
