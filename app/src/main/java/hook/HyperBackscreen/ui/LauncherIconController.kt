package hook.HyperBackscreen.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal object LauncherIconController {
    private const val LAUNCHER_ALIAS_CLASS = "hook.HyperBackscreen.LauncherAlias"

    fun isHidden(context: Context): Boolean {
        return when (context.packageManager.getComponentEnabledSetting(alias(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> false
        }
    }

    fun setHidden(context: Context, hidden: Boolean): Boolean {
        return runCatching {
            context.packageManager.setComponentEnabledSetting(
                alias(context),
                if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                },
                PackageManager.DONT_KILL_APP
            )
            isHidden(context) == hidden
        }.getOrDefault(false)
    }

    private fun alias(context: Context): ComponentName {
        return ComponentName(context.packageName, LAUNCHER_ALIAS_CLASS)
    }
}
