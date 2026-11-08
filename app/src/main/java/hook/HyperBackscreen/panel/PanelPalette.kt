package hook.HyperBackscreen.panel

/**
 * 背屏快捷面板的颜色与圆角。
 *
 * 面板注入在背屏中心进程里，用的是原生 View，拿不到模块的 Compose 主题，所以颜色单独放在这里。
 * 数值逐项对齐 miuix 默认色板（lightColorScheme / darkColorScheme），让面板与主 App 观感一致：
 * 浅色底 #F7F7F7、卡片白、主色 #3482FF；深色底纯黑（背屏是 OLED）、卡片 #242424、主色 #277AF7。
 * 圆角与主 App 的四档规范（HomeUiTokens）一致：面板整体 24dp，开关卡片 16dp。
 */
internal class PanelPalette private constructor(
    /** 面板整体底色（miuix surface）。 */
    val background: Int,
    /** 未开启的卡片底色（miuix surfaceContainer）。 */
    val tile: Int,
    /** 已开启的卡片底色（miuix primary）。 */
    val tileOn: Int,
    /** 卡片标题色。 */
    val onTile: Int,
    /** 已开启卡片上的标题 / 状态色（miuix onPrimary）。 */
    val onTileOn: Int,
    /** 卡片上的状态文字（miuix onSurfaceVariantSummary）。 */
    val summary: Int,
    /** 已开启卡片上的状态文字。 */
    val summaryOn: Int,
    /** 循环切换类卡片上「当前取值」的强调色（miuix primary；深色下取略亮一档，保证小字对比度）。 */
    val accent: Int,
    /** 保存失败等错误提示（miuix error）。 */
    val error: Int,
    /** 顶部模块名。 */
    val title: Int,
) {
    companion object {
        const val PANEL_CORNER_DP = 24
        const val TILE_CORNER_DP = 16

        private val Light = PanelPalette(
            background = 0xFFF7F7F7.toInt(),
            tile = 0xFFFFFFFF.toInt(),
            tileOn = 0xFF3482FF.toInt(),
            onTile = 0xFF000000.toInt(),
            onTileOn = 0xFFFFFFFF.toInt(),
            summary = 0x99000000.toInt(),
            summaryOn = 0xCCFFFFFF.toInt(),
            accent = 0xFF3482FF.toInt(),
            error = 0xFFE94634.toInt(),
            title = 0xFF000000.toInt(),
        )

        private val Dark = PanelPalette(
            background = 0xFF000000.toInt(),
            tile = 0xFF242424.toInt(),
            tileOn = 0xFF277AF7.toInt(),
            onTile = 0xE6FFFFFF.toInt(),
            onTileOn = 0xFFFFFFFF.toInt(),
            summary = 0x80FFFFFF.toInt(),
            summaryOn = 0xCCFFFFFF.toInt(),
            accent = 0xFF4C93FF.toInt(),
            error = 0xFFF12522.toInt(),
            title = 0xE6FFFFFF.toInt(),
        )

        fun of(dark: Boolean): PanelPalette = if (dark) Dark else Light
    }
}
