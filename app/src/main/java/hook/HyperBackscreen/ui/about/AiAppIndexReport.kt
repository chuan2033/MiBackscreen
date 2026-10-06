package hook.HyperBackscreen.ui.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object AiAppIndexReport {
    fun build(runtime: String, appInfo: String): String = buildString {
        appendLine("identity_mapping=runtime.productId -> appInfo.resId")
        appendLine("capture_note=Files are read sequentially; changes during capture may affect comparison.")
        val source = parse(runtime)
        val target = parse(appInfo)
        appendLine("runtime_status=${if (source == null) "unavailable_or_invalid" else "parsed"}")
        appendLine("app_info_status=${if (target == null) "unavailable_or_invalid" else "parsed"}")
        if (source == null || target == null) {
            appendLine("comparison=unavailable; see ai-app-index/runtimeAiApp.txt and appInfo.txt")
            return@buildString
        }
        fun ids(items: JsonArray, key: String) = items.mapNotNull {
            ((it as? JsonObject)?.get(key) as? JsonPrimitive)
                ?.takeIf { value -> value.isString }?.content?.takeIf(String::isNotBlank)
        }
        val sourceIds = ids(source, "productId")
        val targetIds = ids(target, "resId")
        val missing = sourceIds.toSet() - targetIds.toSet()
        val sourceInvalid = source.size - sourceIds.size
        val targetInvalid = target.size - targetIds.size
        appendLine("runtime_entries=${source.size}")
        appendLine("app_info_entries=${target.size}")
        appendLine("runtime_invalid_entries=$sourceInvalid")
        appendLine("app_info_invalid_entries=$targetInvalid")
        appendLine("runtime_duplicate_ids=${sourceIds.size - sourceIds.toSet().size}")
        appendLine("app_info_duplicate_ids=${targetIds.size - targetIds.toSet().size}")
        appendLine("missing_in_app_info_count=${missing.size}")
        appendLine("missing_in_app_info=${JsonArray(missing.sorted().map(::JsonPrimitive))}")
        val missingPaths = source.filter {
            val item = it as? JsonObject
            val path = item?.get("resLocalPath") as? JsonPrimitive
            path == null || !path.isString || path.content.isBlank()
        }.size
        appendLine("runtime_entries_without_resource_path=$missingPaths")
        appendLine("comparison=${when {
            sourceInvalid > 0 || targetInvalid > 0 -> "incomplete"
            missing.isNotEmpty() -> "missing_ids"
            else -> "all_runtime_ids_present"
        }}")
        appendLine("comparison_note=ID presence does not verify resource files or runtime rendering.")
    }

    private fun parse(text: String): JsonArray? = try {
        Json.parseToJsonElement(text) as? JsonArray
    } catch (_: IllegalArgumentException) {
        null
    }
}
