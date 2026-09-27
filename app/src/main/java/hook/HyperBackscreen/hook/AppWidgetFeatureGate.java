package hook.HyperBackscreen.hook;

import androidx.annotation.Nullable;

import hook.HyperBackscreen.common.Constants;

final class AppWidgetFeatureGate {
    private AppWidgetFeatureGate() {
    }

    static boolean isSubscreenAppWidgetSecureKey(@Nullable Object key) {
        return Constants.SECURE_SUBSCREEN_APP_WIDGET_ENABLE.equals(key);
    }
}
