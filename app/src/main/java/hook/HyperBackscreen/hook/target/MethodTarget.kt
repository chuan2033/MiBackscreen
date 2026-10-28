package hook.HyperBackscreen.hook.target

/**
 * 方法体里一次 invoke 的结构判据（不写方法名——被调方法名同样会被混淆）。
 *
 * [declaringClass] 留空表示不限定声明类；[returnType] 留空表示不限定返回类型；
 * [parameterTypes] 为空表示无参方法，null 表示不限定参数列表。
 */
internal class InvokeMethodSpec(
    val declaringClass: String? = null,
    val returnType: String? = null,
    val parameterTypes: List<String>? = null,
)

/**
 * 一个宿主方法的定位规格。
 *
 * [bodyStrings] 是判据：方法体里必须用到这些字符串常量，按整个常量精确相等匹配
 * （不做子串匹配，`"_"` 只命中字面 `"_"`，不会命中 `"android_id"` 这类含下划线的常量），
 * 混淆器不会改字符串，宿主重新分配方法名时判据仍然成立。留空表示不用字符串判据。
 * [usingFieldDescriptors] 是字段引用判据（dex 字段描述符格式 `Lclass;->name:Type`），
 * 方法体必须读写这些字段。留空表示不用字段判据。
 * [usingFieldTypes] 是字段类型判据（`声明类 -> 字段类型`）：字段名会被混淆，
 * 只按类型与声明类匹配方法体引用到的字段。留空表示不用类型判据。
 * [usingNumbers] 是数值常量判据：方法体里必须出现这些常量。留空表示不用数值判据。
 * [invokeMethodSpecs] 是被调方法判据：方法体里必须调用符合规格的方法。
 * 留空表示不用被调方法判据。
 * [pickFirstWhenAmbiguous] 为 true 时，结构命中多个（已知等价别名方法）则取
 * [fallbackName] 同名命中，否则取字典序第一个；默认 false，命中多个视为失败。
 * [fallbackName] 是 DexKit 不可用或判据失配时的兜底混淆名，保持旧版本行为。
 */
internal class MethodTarget(
    val id: String,
    val ownerClass: String,
    val returnType: String?,
    val parameterTypes: List<String>,
    val fallbackName: String,
    val bodyStrings: List<String> = emptyList(),
    val usingFieldDescriptors: List<String> = emptyList(),
    val usingFieldTypes: List<FieldTypeAnchor> = emptyList(),
    val usingNumbers: List<Number> = emptyList(),
    val invokeMethodSpecs: List<InvokeMethodSpec> = emptyList(),
    val pickFirstWhenAmbiguous: Boolean = false,
)

/** 字段类型判据：按声明类 + 字段类型匹配，不含字段名。 */
internal class FieldTypeAnchor(
    val declaredClass: String,
    val typeName: String,
)
