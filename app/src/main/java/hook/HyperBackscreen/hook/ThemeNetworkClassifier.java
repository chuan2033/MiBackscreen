package hook.HyperBackscreen.hook;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import hook.HyperBackscreen.common.Constants;

final class ThemeNetworkClassifier {
    private ThemeNetworkClassifier() {
    }

    static boolean shouldSpoofDeviceIdentity(
            @Nullable String raw,
            @Nullable String requestFlag
    ) {
        if (isEmpty(raw)) return false;
        return isAiAppRequest(raw, requestFlag) || isAiWallpaperRequest(raw);
    }

    static boolean shouldLogDiagnostic(@Nullable String raw) {
        if (isEmpty(raw)) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        return isAiAppRequest(raw, null)
                || isAiWallpaperRequest(raw)
                || lower.contains("rear")
                || lower.contains("subscreen")
                || lower.contains("backscreen")
                || lower.contains("wallpaper")
                || lower.contains("photo")
                || lower.contains("portrait")
                || lower.contains("phonecase")
                || lower.contains("phone_case")
                || lower.contains("case_diy")
                || lower.contains("diy");
    }

    private static boolean isAiAppRequest(
            @NonNull String raw,
            @Nullable String requestFlag
    ) {
        if (raw.contains(Constants.THEME_AI_APP_PAGE_PATH)
                || raw.contains(Constants.THEME_AI_APP_SUBJECT_PATH)) {
            return true;
        }
        if (raw.contains(Constants.THEME_AI_APP_DETAIL_PATH)) {
            return Constants.THEME_AI_APP_REQUEST_FLAG_VALUE.equals(requestFlag);
        }
        return false;
    }

    private static boolean isAiWallpaperRequest(@NonNull String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (containsAny(
                lower,
                "aiwallpaper",
                "ai_wallpaper",
                "ai-wallpaper",
                "aigc",
                "ai_generated",
                "ai-generated",
                "ai_photo",
                "aiphoto")) {
            return true;
        }
        boolean aiWallpaper = lower.contains("ai") && lower.contains("wallpaper");
        boolean rearWallpaperFeature = containsAny(
                lower,
                "phonecase",
                "phone_case",
                "phone-case",
                "case_diy",
                "portrait",
                "photo",
                "diy");
        boolean rearSurface = containsAny(
                lower,
                "rear",
                "subscreen",
                "backscreen",
                "wallpaper",
                "ai");
        return aiWallpaper || (rearWallpaperFeature && rearSurface);
    }

    private static boolean containsAny(@NonNull String value, @NonNull String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty() || "null".equals(value);
    }
}
