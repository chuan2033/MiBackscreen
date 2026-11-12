package hook.HyperBackscreen.ui.theme

internal enum class BottomBarStyle(val prefValue: String) {
    STANDARD("standard"),
    FLOATING("floating"),
    LIQUID("liquid"),
    GLASS("glass");

    val isFloating: Boolean get() = this != STANDARD

    companion object {
        fun fromPrefValue(value: String): BottomBarStyle =
            entries.firstOrNull { it.prefValue == value } ?: STANDARD
    }
}
