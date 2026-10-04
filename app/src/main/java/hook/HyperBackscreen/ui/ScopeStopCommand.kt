package hook.HyperBackscreen.ui

import hook.HyperBackscreen.common.Constants

private val packagePattern = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

/** Automatic app refreshes cannot reboot the device. */
internal fun scopeStopCommand(packageName: String): Array<String>? =
    if (packagePattern.matches(packageName)) arrayOf("su", "-c", "am force-stop $packageName") else null

/** Reboot is available only to the explicitly confirmed scope selection. */
internal fun scopeActionCommands(scopes: List<String>, allowSystemReboot: Boolean = false): List<Array<String>>? {
    if (scopes.any { it != Constants.SYSTEM_PACKAGE && scopeStopCommand(it) == null }) return null
    if (Constants.SYSTEM_PACKAGE in scopes) {
        return if (allowSystemReboot) listOf(arrayOf("su", "-c", "reboot")) else null
    }
    return scopes.distinct().map { scopeStopCommand(it) ?: return null }
}
