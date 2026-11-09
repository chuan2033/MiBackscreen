package hook.HyperBackscreen.hook.subscreen;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Outline;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import hook.HyperBackscreen.common.Constants;

/**
 * 背屏卡片里的小部件视图：小部件始终按自己的「自然尺寸」测量、排版（和桌面上一样），
 * 再整体等比缩放、居中放进卡片的安全区（让开左侧摄像头挖孔、四周留白），并切圆角。
 *
 * <p>做法参考 REAREye（GPLv3）的 AppWidgetFitHostView：卡片尺寸只改变显示时的缩放，
 * 不拿来给小部件排版，所以 4x2 不会被挤、4x4 这种高卡片按高度缩小而不是被压扁裁掉。
 * 缩放在 {@link #onLayout} 里 super 之后设置，不会被 AppWidgetHostView 自己的排版冲掉；
 * 触摸分发时系统会自动按子 View 的矩阵做逆变换，点击位置是对的。
 */
final class RearWidgetHostView extends AppWidgetHostView {
    private static final String TAG = Constants.LOG_TAG;
    /** 背屏 976x596 左侧 0..296px 是摄像头挖孔；系统没给 DisplayCutout 时按这个兜底。 */
    private static final int KNOWN_SAFE_LEFT_PX = 298;
    /**
     * 面板打开时整个背屏窗口按约 0.95 缩小显示（景深效果，不在 View 属性上），
     * 排版坐标里的留白落到屏幕上会再大一圈，所以这里留得比看起来要的小。
     */
    private static final float EDGE_MARGIN_DP = 8f;
    /**
     * 卡片左缘相对挖孔边界（298px）往里收：摄像头圈在屏幕上画到 x≈291，
     * 排版坐标 ~290 经窗口缩小后落在 x≈301，正好贴着圈、不压上去。
     */
    private static final float CUTOUT_OVERLAP_DP = 3f;
    private static final float CORNER_DP = 16f;
    /** provider 没声明最小尺寸时的自然尺寸（与 REAREye 一致）。 */
    private static final int FALLBACK_WIDTH_DP = 240;
    private static final int FALLBACK_HEIGHT_DP = 120;

    /** provider 的 minWidth/minHeight 是按主屏密度换算的像素。 */
    private final float providerDensity;
    private final float density;
    private int naturalW = 1;
    private int naturalH = 1;
    private int notifiedW = -1;
    private int notifiedH = -1;
    private boolean notifyPosted;
    /** 上次排版时安全区的宽高比；测量时拿不到准确位置，以排版时的为准。 */
    private float boxAspect;

    RearWidgetHostView(@NonNull Context context) {
        super(context);
        Context app = context.getApplicationContext();
        providerDensity = (app != null ? app : context).getResources().getDisplayMetrics().density;
        density = context.getResources().getDisplayMetrics().density;
        setClipToPadding(false);
        setClipChildren(true);
    }

    private final Runnable notifySize = this::notifyNaturalSize;

    /** 把自然尺寸告诉小部件（按它排版、挑响应式布局），不是卡片尺寸。 */
    private void notifyNaturalSize() {
        notifyPosted = false;
        if (naturalW == notifiedW && naturalH == notifiedH) return;
        int widthDp = (int) Math.ceil(naturalW / density);
        int heightDp = (int) Math.ceil(naturalH / density);
        try {
            updateAppWidgetSize(new Bundle(), widthDp, heightDp, widthDp, heightDp);
            notifiedW = naturalW;
            notifiedH = naturalH;
        } catch (Throwable error) {
            Log.w(TAG, "Rear widget size update failed", error);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        computeNaturalSize(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(MeasureSpec.makeMeasureSpec(naturalW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(naturalH, MeasureSpec.EXACTLY));
        setMeasuredDimension(resolveSize(naturalW, widthMeasureSpec), resolveSize(naturalH, heightMeasureSpec));
        if ((naturalW != notifiedW || naturalH != notifiedH) && !notifyPosted) {
            notifyPosted = true;
            post(notifySize); // 不在测量 / 排版过程中调 Binder
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int viewportW = getMeasuredWidth();
        int viewportH = getMeasuredHeight();
        try {
            // AppWidgetHostView 可能在这里按尺寸挑一份响应式 RemoteViews 重新展开，它要看到的是自然尺寸。
            setMeasuredDimension(naturalW, naturalH);
            super.onLayout(changed, 0, 0, naturalW, naturalH);
            layoutNaturalChildren();
        } finally {
            setMeasuredDimension(viewportW, viewportH);
        }
        applyFit(right - left, bottom - top);
    }

    /** 安全区：让开摄像头挖孔，四周留白；内容等比缩放后居中。 */
    private void applyFit(int width, int height) {
        int margin = Math.round(EDGE_MARGIN_DP * density);
        int safeLeft = leftEdge(width, height, margin);
        float boxW = Math.max(1, width - safeLeft - margin);
        float boxH = Math.max(1, height - margin * 2);
        float aspect = boxW / boxH;
        if (Math.abs(aspect - boxAspect) > 0.01f) {
            boxAspect = aspect;
            post(this::requestLayout); // 按准确的比例重新量一次，让小部件铺满卡片
        }
        float scale = Math.min(boxW / naturalW, boxH / naturalH);
        float originX = safeLeft + (boxW - naturalW * scale) / 2f;
        float originY = margin + (boxH - naturalH * scale) / 2f;
        float corner = CORNER_DP * density / scale;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setTranslationX(originX + child.getLeft() * scale - child.getLeft());
            child.setTranslationY(originY + child.getTop() * scale - child.getTop());
            child.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            });
            child.setClipToOutline(true);
        }
    }

    /** 内容区左边缘：紧贴摄像头圈；左边没有挖孔时用普通边距。 */
    private int leftEdge(int width, int height, int margin) {
        int inset = safeLeftInset(width, height);
        return inset > 0 ? Math.max(0, inset - Math.round(CUTOUT_OVERLAP_DP * density)) : margin;
    }

    /** 摄像头挖孔在屏幕左侧；面板翻页靠位移，只累加布局位置、不算位移。 */
    private int safeLeftInset(int width, int height) {
        int leftInWindow = 0;
        for (View p = this; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            leftInWindow += p.getLeft() - (p == this ? 0 : p.getScrollX());
        }
        WindowInsets insets = getRootWindowInsets();
        DisplayCutout cutout = insets != null ? insets.getDisplayCutout() : null;
        int safe;
        if (cutout != null && cutout.getSafeInsetLeft() > 0) {
            safe = cutout.getSafeInsetLeft();
        } else {
            // 这台设备的背屏横屏 976x596，左侧挖孔贯穿全高；拿不到 Insets 时按已知尺寸兜底。
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenW = Math.max(metrics.widthPixels, metrics.heightPixels);
            int screenH = Math.min(metrics.widthPixels, metrics.heightPixels);
            safe = screenW >= 900 && screenH <= 700 ? KNOWN_SAFE_LEFT_PX : 0;
        }
        return Math.max(0, safe - leftInWindow);
    }

    /**
     * 自然尺寸 = provider 声明的最小尺寸（和写死尺寸的子 View 取大），再按卡片安全区的宽高比把
     * 不足的一边补齐：小部件铺满整张卡片，像日历这种声明得偏扁的也有足够高度，不会挤成一团。
     */
    private void computeNaturalSize(int viewportW, int viewportH) {
        int fixedW = 0;
        int fixedH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            int[] fixed = fixedExtent(getChildAt(i));
            fixedW = Math.max(fixedW, fixed[0]);
            fixedH = Math.max(fixedH, fixed[1]);
        }
        AppWidgetProviderInfo info = getAppWidgetInfo();
        naturalW = dimension(info != null ? info.minWidth : 0, FALLBACK_WIDTH_DP, fixedW);
        naturalH = dimension(info != null ? info.minHeight : 0, FALLBACK_HEIGHT_DP, fixedH);
        float aspect = boxAspect;
        if (aspect <= 0f) {
            int margin = Math.round(EDGE_MARGIN_DP * density);
            float boxW = viewportW - leftEdge(viewportW, viewportH, margin) - margin;
            float boxH = viewportH - margin * 2f;
            if (boxW <= 0 || boxH <= 0) return;
            aspect = boxW / boxH;
        }
        if (naturalW < naturalH * aspect) {
            naturalW = Math.round(naturalH * aspect);
        } else {
            naturalH = Math.round(naturalW / aspect);
        }
    }

    private int dimension(int providerPx, int fallbackDp, int fixedPx) {
        double dp = providerPx > 0 ? providerPx / (double) providerDensity : fallbackDp;
        int content = Math.max(1, (int) Math.ceil(dp * density));
        return Math.max(content, Math.max(0, fixedPx));
    }

    /** 按自然画布摆放子 View（FrameLayout 的规则），不受外面卡片尺寸影响。 */
    private void layoutNaturalChildren() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
            int gravity = params.gravity == -1 ? Gravity.TOP | Gravity.START : params.gravity;
            int absolute = Gravity.getAbsoluteGravity(gravity, getLayoutDirection());
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            int x;
            switch (absolute & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.CENTER_HORIZONTAL:
                    x = (naturalW - w) / 2 + params.leftMargin - params.rightMargin;
                    break;
                case Gravity.RIGHT:
                    x = naturalW - w - params.rightMargin;
                    break;
                default:
                    x = params.leftMargin;
                    break;
            }
            int y;
            switch (gravity & Gravity.VERTICAL_GRAVITY_MASK) {
                case Gravity.CENTER_VERTICAL:
                    y = (naturalH - h) / 2 + params.topMargin - params.bottomMargin;
                    break;
                case Gravity.BOTTOM:
                    y = naturalH - h - params.bottomMargin;
                    break;
                default:
                    y = params.topMargin;
                    break;
            }
            child.layout(x, y, x + w, y + h);
        }
    }

    /**
     * 写死尺寸的子 View 可能比 provider 声明的还大：照顾它们的下限，但不把 wrap_content 的文字
     * 当成无限宽，也不展开本来就该滚动的列表。返回 {宽, 高}（含外边距）。
     */
    @NonNull
    private static int[] fixedExtent(@NonNull View view) {
        if (view.getVisibility() == GONE) return new int[]{0, 0};
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int width = Math.max(params != null ? Math.max(0, params.width) : 0, view.getMinimumWidth());
        int height = Math.max(params != null ? Math.max(0, params.height) : 0, view.getMinimumHeight());
        if (view instanceof ViewGroup && !(view instanceof ScrollView)
                && !(view instanceof HorizontalScrollView) && !(view instanceof AdapterView)) {
            ViewGroup group = (ViewGroup) view;
            boolean horizontal = group instanceof LinearLayout
                    && ((LinearLayout) group).getOrientation() == LinearLayout.HORIZONTAL;
            boolean vertical = group instanceof LinearLayout
                    && ((LinearLayout) group).getOrientation() == LinearLayout.VERTICAL;
            int childrenW = 0;
            int childrenH = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int[] fixed = fixedExtent(child);
                childrenW = horizontal ? childrenW + fixed[0] : Math.max(childrenW, fixed[0]);
                childrenH = vertical ? childrenH + fixed[1] : Math.max(childrenH, fixed[1]);
            }
            width = Math.max(width, childrenW + group.getPaddingLeft() + group.getPaddingRight());
            height = Math.max(height, childrenH + group.getPaddingTop() + group.getPaddingBottom());
        }
        if (params instanceof MarginLayoutParams) {
            MarginLayoutParams margins = (MarginLayoutParams) params;
            width += margins.leftMargin + margins.rightMargin;
            height += margins.topMargin + margins.bottomMargin;
        }
        return new int[]{width, height};
    }
}
