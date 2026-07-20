package hook.HyperBackscreen.ui.util

import android.os.Build

internal fun currentDeviceName(): String {
    val marketName = getSystemProperty("ro.product.vendor.marketname")
        ?: getSystemProperty("ro.product.marketname")
        ?: getSystemProperty("ro.miui.marketname")
    if (!marketName.isNullOrBlank() && !marketName.startsWith("sdk_")) {
        return marketName
    }
    return "${Build.MANUFACTURER} ${Build.MODEL}"
}

internal fun currentSystemVersion(): String {
    return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}

internal fun currentHyperOSVersion(): String {
    val osName = getSystemProperty("ro.mi.os.version.name")
    if (!osName.isNullOrBlank()) {
        return osName
    }
    return "MIUI"
}

private fun getSystemProperty(key: String): String? {
    return try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java, String::class.java)
        val value = method.invoke(null, key, "") as? String
        if (value.isNullOrBlank()) null else value
    } catch (_: Exception) {
        null
    }
}
