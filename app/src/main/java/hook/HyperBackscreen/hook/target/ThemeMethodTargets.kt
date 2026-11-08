package hook.HyperBackscreen.hook.target

import hook.HyperBackscreen.common.Constants

/**
 * 主题商店（com.android.thememanager）里签名不唯一、需要 DexKit 方法体锚点的混淆方法清单。
 *
 * 判据来自 `dexdump -d` 的方法体指令：
 * 字符串常量与系统类字段引用是稳定锚点（混淆器不改字符串和系统 API 名字），
 * 在对应宿主类的完整 dex 上验证过唯一性。
 */
internal object ThemeMethodTargets {

    // ---- DeviceUtils：device / model / version 三个无参 String getter 签名完全相同 ----

    /** 返回 device（Build.DEVICE）。类内引用 Build.DEVICE 的 6 个方法里唯一的 String 返回。 */
    @JvmField
    val DEVICE_UTILS_DEVICE = MethodTarget(
        id = "device_utils_device",
        ownerClass = Constants.THEME_DEVICE_UTILS_CLASS,
        returnType = "java.lang.String",
        parameterTypes = emptyList(),
        bodyStrings = emptyList(),
        usingFieldDescriptors = listOf("Landroid/os/Build;->DEVICE:Ljava/lang/String;"),
        fallbackName = "y",
    )

    /** 返回 model（Build.MODEL）。类内唯一引用 Build.MODEL 的方法。 */
    @JvmField
    val DEVICE_UTILS_MODEL = MethodTarget(
        id = "device_utils_model",
        ownerClass = Constants.THEME_DEVICE_UTILS_CLASS,
        returnType = "java.lang.String",
        parameterTypes = emptyList(),
        bodyStrings = emptyList(),
        usingFieldDescriptors = listOf("Landroid/os/Build;->MODEL:Ljava/lang/String;"),
        fallbackName = "cdj",
    )

    /** 返回 version（Build.VERSION.RELEASE + "_" + INCREMENTAL）。类内唯一使用字符串 "_" 的方法。 */
    @JvmField
    val DEVICE_UTILS_VERSION = MethodTarget(
        id = "device_utils_version",
        ownerClass = Constants.THEME_DEVICE_UTILS_CLASS,
        returnType = "java.lang.String",
        parameterTypes = emptyList(),
        bodyStrings = listOf("_"),
        fallbackName = "z",
    )

    // ---- OnlineService：同样模式的 version getter ----

    /** 返回 version。类内唯一使用字符串 "_" 的方法。 */
    @JvmField
    val ONLINE_SERVICE_VERSION = MethodTarget(
        id = "online_service_version",
        ownerClass = Constants.THEME_ONLINE_SERVICE_CLASS,
        returnType = "java.lang.String",
        parameterTypes = emptyList(),
        bodyStrings = listOf("_"),
        fallbackName = "fnq8",
    )

    // ---- SettingHelper$Companion：CMF getter，两个无参 String 方法之一 ----

    /** 返回 CMF 字符串。方法体里读系统属性 "vendor.wallpaper.color.flag"，全类唯一。 */
    @JvmField
    val SETTING_HELPER_CMF = MethodTarget(
        id = "setting_helper_cmf",
        ownerClass = Constants.THEME_SETTING_HELPER_COMPANION_CLASS,
        returnType = "java.lang.String",
        parameterTypes = emptyList(),
        bodyStrings = listOf("vendor.wallpaper.color.flag"),
        fallbackName = "zy",
    )

    // ---- MiuiUtils：43 个无参布尔方法之一 ----

    /** 18 Pro 版本判定（< 10278 返回 true）。类内唯一使用数值常量 10278 的方法。 */
    @JvmField
    val MIUI_UTILS_IS_REAR = MethodTarget(
        id = "miui_utils_is_rear",
        ownerClass = Constants.THEME_MIUI_UTILS_CLASS,
        returnType = "boolean",
        parameterTypes = emptyList(),
        fallbackName = "zurt",
        usingNumbers = listOf(10278),
    )

    // ---- DeviceUtils：isSupportRearScreen 标志 ----

    /**
     * 返回 isSupportRearScreen。无参布尔方法里唯一调用 `miuix.os.DeviceHelper` 无参布尔方法的
     * （`DeviceHelper.toq()Z`）；同类其余 DeviceHelper 调用都带 Context 参数。
     */
    @JvmField
    val DEVICE_UTILS_IS_SUPPORT_REAR = MethodTarget(
        id = "device_utils_is_support_rear",
        ownerClass = Constants.THEME_DEVICE_UTILS_CLASS,
        returnType = "boolean",
        parameterTypes = emptyList(),
        fallbackName = "a",
        invokeMethodSpecs = listOf(
            InvokeMethodSpec(
                declaringClass = "miuix.os.DeviceHelper",
                returnType = "boolean",
                parameterTypes = emptyList(),
            )
        ),
    )

    // ---- RearScreenPresetRepository：AI 预设列表挂起读取 ----

    /**
     * 返回预设列表的挂起方法。签名 `(Continuation)Object` 的候选有 `k` 与协程状态机 `fn3e`；
     * `k` 是唯一会调用 `(ArrayList,String)List` 读取方法（`zurt`）的。
     */
    @JvmField
    val PRESET_REPOSITORY_LIST = MethodTarget(
        id = "preset_repository_list",
        ownerClass = Constants.THEME_REAR_PRESET_REPOSITORY_CLASS,
        returnType = "java.lang.Object",
        parameterTypes = listOf("kotlin.coroutines.Continuation"),
        fallbackName = "k",
        invokeMethodSpecs = listOf(
            InvokeMethodSpec(
                declaringClass = Constants.THEME_REAR_PRESET_REPOSITORY_CLASS,
                returnType = "java.util.List",
                parameterTypes = listOf("java.util.ArrayList", "java.lang.String"),
            )
        ),
    )

    // ---- okhttp3.Request：请求 URL 访问器 ----

    /**
     * 返回请求 URL（`HttpUrl`）。`cdj` 与 `n` 是 R8 生成的等价别名（同一段代码、同读
     * `Request.k` 字段），取到任一即可；字段名会混淆，按「声明类 + 字段类型」匹配。
     */
    @JvmField
    val REQUEST_URL = MethodTarget(
        id = "request_url",
        ownerClass = "okhttp3.Request",
        returnType = "okhttp3.HttpUrl",
        parameterTypes = emptyList(),
        fallbackName = "cdj",
        usingFieldTypes = listOf(
            FieldTypeAnchor(
                declaredClass = "okhttp3.Request",
                typeName = "okhttp3.HttpUrl",
            )
        ),
        pickFirstWhenAmbiguous = true,
    )
}
