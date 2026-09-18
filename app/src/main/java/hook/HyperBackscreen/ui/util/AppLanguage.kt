package hook.HyperBackscreen.ui.util

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * 应用内语言。用系统 per-app language（API 33+）实现，语言由系统持久化，不占模块偏好。
 * [index] 是设置页下拉框的顺序。
 */
enum class AppLanguage(val index: Int, val tag: String?) {
    SYSTEM(0, null),
    CHINESE(1, "zh-CN"),
    ENGLISH(2, "en");

    companion object {
        fun fromIndex(index: Int): AppLanguage =
            values().firstOrNull { it.index == index } ?: SYSTEM

        /** 当前应用语言；跟随系统时返回 [SYSTEM]。 */
        fun current(context: Context): AppLanguage {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) return SYSTEM
            val tags = locales.toLanguageTags()
            return values().firstOrNull { it.tag != null && it.tag.equals(tags, ignoreCase = true) }
                ?: SYSTEM
        }

        /** 应用语言；传 [SYSTEM] 表示跟随系统。 */
        fun apply(context: Context, language: AppLanguage) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales = language.tag
                ?.let { LocaleList.forLanguageTags(it) }
                ?: LocaleList.getEmptyLocaleList()
        }
    }
}
