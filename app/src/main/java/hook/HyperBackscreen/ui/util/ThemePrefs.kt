package hook.HyperBackscreen.ui.util

import android.content.Context

enum class ThemeMode(val index: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    /** 将用户选择解析为最终是否使用深色主题。 */
    fun resolve(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromIndex(i: Int): ThemeMode = entries.firstOrNull { it.index == i } ?: SYSTEM
    }
}

object ThemePrefs {
    private const val PREF_NAME = "app_ui_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return ThemeMode.fromIndex(prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.index))
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode.index)
            .apply()
    }
}
