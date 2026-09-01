package hook.HyperBackscreen.common;

public final class RearShellLayout {
    public final float contentWidth;
    public final float contentHeight;
    public final float leftMargin;
    public final float topMargin;
    public final float rightMargin;
    public final float bottomMargin;

    public RearShellLayout(
            float contentWidth,
            float contentHeight,
            float leftMargin,
            float topMargin,
            float rightMargin,
            float bottomMargin
    ) {
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
        this.leftMargin = leftMargin;
        this.topMargin = topMargin;
        this.rightMargin = rightMargin;
        this.bottomMargin = bottomMargin;
    }

    public int shellWidth() {
        return Math.round(contentWidth + leftMargin + rightMargin);
    }

    public int shellHeight() {
        return Math.round(contentHeight + topMargin + bottomMargin);
    }

    public float topBlackHeightFor(int bitmapWidth, int bitmapHeight) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || contentWidth <= 0f || contentHeight <= 0f) {
            return 0f;
        }
        float shellAspect = contentWidth / contentHeight;
        float bitmapAspect = (float) bitmapWidth / (float) bitmapHeight;
        if (Math.abs(bitmapAspect - shellAspect) <= 0.001f) {
            return 0f;
        }
        float top = bitmapHeight - (bitmapWidth / shellAspect);
        if (top >= 0f) {
            return 0f;
        }
        return -top;
    }

    public Rect contentRectFor(int bitmapWidth, int bitmapHeight) {
        float topBlackHeight = topBlackHeightFor(bitmapWidth, bitmapHeight);
        return new Rect(
                leftMargin,
                topMargin + topBlackHeight,
                shellWidth() - rightMargin,
                shellHeight() - bottomMargin);
    }

    public static final class Rect {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        private Rect(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
