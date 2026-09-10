package hook.HyperBackscreen.ui.util

internal object RearDisplayCompatibility {
    const val BUILTIN_PRESENTATION_PROPERTY = "vendor.display.builtin_presentation"

    fun builtinPresentationValue(): String? {
        return readSystemProperty(BUILTIN_PRESENTATION_PROPERTY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun isBuiltinPresentationDisabled(): Boolean {
        return builtinPresentationValue() == "0"
    }

    private fun readSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            get.invoke(null, key) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
