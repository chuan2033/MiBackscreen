package hook.HyperBackscreen.ui.about

import hook.HyperBackscreen.ui.components.DetailPageScaffold
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.AboutArrowPreference
import hook.HyperBackscreen.ui.components.CardBlock

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
        // 背屏实时歌词订阅 SuperLyric 3.x / 4.x 服务。仓库 LICENSE 为 LGPL-2.1，
        // AIDL / 源码文件头写的是 LGPL-3.0-or-later，两者一并列出。
        name = "SuperLyricApi",
        license = "LGPL-2.1 / LGPL-3.0-or-later",
        url = "https://github.com/HChenX/SuperLyricApi"
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
    DetailPageScaffold(
        title = stringResource(R.string.license_title),
        onBack = onBack,
    ) {
        items(licenses) { license ->
            CardBlock {
                AboutArrowPreference(
                    title = license.name,
                    summary = "${license.license} · ${license.url}",
                    url = license.url
                )
            }
        }
    }
}
