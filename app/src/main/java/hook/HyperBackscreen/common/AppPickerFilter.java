package hook.HyperBackscreen.common;

import androidx.annotation.Nullable;

import java.util.Locale;

public final class AppPickerFilter {
    private AppPickerFilter() {
    }

    public static boolean matches(@Nullable String label, @Nullable String packageName, @Nullable String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) return true;
        return contains(label, normalizedQuery) || contains(packageName, normalizedQuery);
    }

    private static boolean contains(@Nullable String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
