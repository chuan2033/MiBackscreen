package hook.HyperBackscreen.target

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass

/**
 * 宿主 dex 特征分析工具。给 [HostTargets] 挑判据、验证唯一性时跑。
 *
 * 默认跳过；指定 `host.apk.dir` 指向 `.tmp/host` 才执行，例如：
 * `gradlew :app:testDebugUnitTest --tests "hook.HyperBackscreen.target.HostDexAnalysisTest" -Dhost.apk.dir=D:/Ai/MiBackscreen/.tmp/host`
 */
class HostDexAnalysisTest {

    private fun apkDir(): File? =
        System.getProperty("host.apk.dir")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test
    fun smokeTest() {
        val dir = apkDir() ?: return
        val apk = File(dir, "subscreencenter.apk")
        assumeTrue("subscreencenter.apk 不存在", apk.isFile)
        DexKitBridge.create(apk.absolutePath).use { bridge ->
            val classes = bridge.findClass(FindClass.create().searchPackages("e2"))
            println("[smoke] e2 包类数量=${classes.size}")
            println("[smoke] 前十个=${classes.map { it.name }.take(10)}")
        }
    }
}
