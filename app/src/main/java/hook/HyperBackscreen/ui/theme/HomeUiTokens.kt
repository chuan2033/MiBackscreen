package hook.HyperBackscreen.ui.theme

import androidx.compose.ui.unit.dp

object HomeUiTokens {
    val ListHorizontalPadding = 16.dp
    val InfoRowHeight = 56.dp

    /*
     * 圆角四档。新加的 UI 只从这四档里取，不要再写零散的数值：
     * - Small：图标底板、行内小块、代码块；
     * - Card：列表卡片、状态卡、背屏面板里的开关卡片；
     * - Large：大卡片与浮层（日志浮层、捐赠卡、背屏面板整体）；
     * - Page：页面级（二级页转场时一级页缩成的卡片）。
     * 胶囊形按钮 / 标签（高度一半做圆角）不在此列，直接用 50% 圆角。
     */
    val CornerSmall = 12.dp
    val CornerCard = 16.dp
    val CornerLarge = 24.dp
    val CornerPage = 28.dp

    /** 历史名字，等同 [CornerCard]。 */
    val CardCornerRadius = CornerCard

    val AboutHeaderTopPadding = 48.dp
    val AboutHeaderBottomPadding = 42.dp
    val AboutLogoSize = 100.dp
    /** 关于页图标白色底板的圆角。 */
    val AboutLogoCornerRadius = 26.dp
    /** 图标在白色底板里的尺寸。图标是横向宽图，78dp 时左右仍留有白边、不贴边。 */
    val AboutLogoInnerSize = 78.dp
    /**
     * 图标的视觉居中修正：右下角的闪电把外框撑宽，几何居中时矩形主体和视觉重心都偏左偏下，
     * 往右、往上挪一点才看起来在底板正中（按截图实测的重心偏移换算）。
     */
    val AboutLogoOpticalOffsetX = 3.5.dp
    val AboutLogoOpticalOffsetY = (-1.5).dp

    val AboutAvatarSize = 30.dp

    val AboutAvatarCornerRadius = 20.dp

    val AboutHeaderSpacing = 2.dp
    val AboutVersionSpacing = 4.dp

    /** 二级页列表首尾留白：顶栏下方 / 导航栏上方。 */
    val DetailListTopSpacing = 12.dp
    val DetailListBottomSpacing = 24.dp
}
