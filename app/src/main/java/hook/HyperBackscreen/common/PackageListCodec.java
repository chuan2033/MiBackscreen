package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class PackageListCodec {
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s,;；，、]+");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    private PackageListCodec() {
    }

    @NonNull
    public static LinkedHashSet<String> parse(@Nullable String raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return result;
        String[] parts = SPLIT_PATTERN.split(raw);
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            if (PACKAGE_PATTERN.matcher(value).matches()) {
                result.add(value);
            }
        }
        return result;
    }

    @NonNull
    public static String encode(@Nullable Collection<String> packages) {
        if (packages == null || packages.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        Set<String> normalized = new LinkedHashSet<>();
        for (String packageName : packages) {
            if (packageName == null) continue;
            String value = packageName.trim();
            if (value.isEmpty()) continue;
            if (PACKAGE_PATTERN.matcher(value).matches()) {
                normalized.add(value);
            }
        }
        for (String packageName : normalized) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(packageName);
        }
        return builder.toString();
    }
}
