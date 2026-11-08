package hook.HyperBackscreen.hook.target

import java.io.File
import java.lang.reflect.Modifier

/**
 * 主题商店（com.android.thememanager）里要按结构定位的类。
 *
 * 判据来自主题商店 11.5.6.0（vc 11560）`dexdump -d`：字符串常量在给定包内只命中一个类，
 * 再用形状校验兜一层，防止宿主改版后命中别的同包类。
 */
internal object ThemeClassTargets {
    // 以下三个类名在主题商店里没有被混淆（Kotlin 数据类 / 密封类），直接按名字加载。
    const val VIDEO_EDITOR_CONFIG_CLASS = "com.android.thememanager.videoedit.VideoEditorConfig"
    const val VIDEO_EDITOR_CONFIG_BUILDER_CLASS = "$VIDEO_EDITOR_CONFIG_CLASS\$Builder"
    const val CHECK_VIDEO_RESULT_CLASS = "com.android.thememanager.util.VideoCheckUtils\$CheckVideoResult"

    /**
     * 背屏 / 锁屏共用的视频校验拦截器里，真正做时长 / 帧率 / 分辨率判断的协程体
     * `VideoCheckForDepthInterceptor$checkVideo$2`：`invokeSuspend` 直接返回 CheckVideoResult。
     * 字符串 "checkVideo: last frameRate = " 在 `com.personalizedEditor.interceptor` 里只出现在这个类；
     * "checkVideo: is horizontal video" 会同时命中普通视频拦截器，不能单用。
     * 形状：有 `invokeSuspend(Object)`，且持有一个 VideoEditorConfig 字段（`$videoConfig`）。
     */
    @JvmField
    val VIDEO_DEPTH_CHECK = ClassTarget(
        id = "theme_video_depth_check",
        packages = listOf("com.personalizedEditor.interceptor"),
        strings = listOf("checkVideo: last frameRate = "),
        fallbackNames = listOf(
            "com.personalizedEditor.interceptor.VideoCheckForDepthInterceptor\$checkVideo\$2"
        ),
        exactStrings = true,
        shape = { cls ->
            cls.declaredMethods.any {
                it.name == "invokeSuspend" && it.parameterTypes.contentEquals(arrayOf(Any::class.java))
            } && cls.declaredFields.any { it.type.name == VIDEO_EDITOR_CONFIG_CLASS }
        },
    )

    /**
     * `VideoSplitor`：用 MediaExtractor + MediaMuxer 把视频轨、音轨分别写出。
     * 私有方法 `k` 里的 "Saw input EOS." 在 `com.android.thememanager.util` 包内唯一。
     * 形状：唯一一个静态 `(File, File, File) -> android.util.Pair` 方法（混淆名，当前是 `toq`）。
     */
    @JvmField
    val VIDEO_SPLITOR = ClassTarget(
        id = "theme_video_splitor",
        packages = listOf("com.android.thememanager.util"),
        strings = listOf("Saw input EOS."),
        fallbackNames = listOf("com.android.thememanager.util.VideoSplitor"),
        exactStrings = true,
        shape = { cls ->
            cls.declaredMethods.count {
                Modifier.isStatic(it.modifiers)
                        && it.returnType == android.util.Pair::class.java
                        && it.parameterTypes.contentEquals(
                    arrayOf(File::class.java, File::class.java, File::class.java)
                )
            } == 1
        },
    )
}
