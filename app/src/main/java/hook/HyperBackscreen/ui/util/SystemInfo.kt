package hook.HyperBackscreen.ui.util

import android.content.Context
import android.content.pm.PackageManager
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
    // version.name is only the major release (e.g. OS4.0); keep the full ROM build.
    val version = getSystemProperty("ro.mi.os.version.incremental")
        ?: Build.VERSION.INCREMENTAL?.takeIf { it.matches(Regex("(?:OS|V)\\d.*")) }
        ?: getSystemProperty("ro.mi.os.version.name")
        ?: "MIUI"
    val extension = getSystemProperty("ro.mi.xms.version.incremental")?.trim()?.trimStart('.')
    return if (version.startsWith("OS") && !extension.isNullOrBlank() &&
        !version.endsWith(".$extension")
    ) "$version.$extension" else version
}

internal fun currentPackageVersion(context: Context, packageName: String): String {
    return try {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
        info.versionName?.takeIf { it.isNotBlank() } ?: "—"
    } catch (_: Exception) {
        "—"
    }
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
