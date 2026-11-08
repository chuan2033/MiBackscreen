package hook.HyperBackscreen.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底栏与 Snackbar 的位置都从这里算，不在调用处写偏移量。
 *
 * 三种底栏统一成同一个几何：非悬浮底栏贴底；两种悬浮底栏的底边都离导航栏（手势条）顶部
 * [FloatingGap]；Snackbar 停在底栏上方 [SnackbarGap]。
 */
internal object BottomBarMetrics {
    /** 底栏内容高度：miuix NavigationBar 的 ItemHeight，也是液态玻璃底栏的高度。 */
    val BarHeight = 64.dp

    /** 悬浮底栏底边到导航栏 inset 顶部的距离。 */
    val FloatingGap = 4.dp

    /** Snackbar 与其下方元素（底栏或导航栏）的距离。 */
    val SnackbarGap = 12.dp

    /**
     * miuix FloatingNavigationBar 自己会在底部留白：有导航栏 inset 时是 26dp + inset，没有时 36dp。
     * 返回需要往下挪多少，才能让它的底边也落在「inset 之上 [FloatingGap]」。
     */
    fun miuixFloatingBarOffset(navigationInset: Dp): Dp {
        val internalPadding = if (navigationInset > 0.dp) 26.dp + navigationInset else 36.dp
        return internalPadding - navigationInset - FloatingGap
    }

    /** Snackbar 在导航栏 inset 之上的底部边距；二级页没有底栏，只留 [SnackbarGap]。 */
    fun snackbarBottomPadding(onMainPage: Boolean, floatingBar: Boolean): Dp = when {
        !onMainPage -> SnackbarGap
        floatingBar -> BarHeight + FloatingGap + SnackbarGap
        else -> BarHeight + SnackbarGap
    }
}
