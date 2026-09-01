package hook.HyperBackscreen.common;

import androidx.annotation.Nullable;

import java.util.Set;

public final class RearScreenWakeMatcher {
    private RearScreenWakeMatcher() {
    }

    public static boolean isRearDoubleTapWake(int groupId, @Nullable Object details) {
        return groupId == 1 && Constants.WAKE_REASON_DOUBLE_TAP.equals(details);
    }

    public static boolean matchesAnyPackage(@Nullable String rawPackages, @Nullable String... candidates) {
        if (candidates == null || candidates.length == 0) return false;
        Set<String> packages = PackageListCodec.parse(rawPackages);
        if (packages.isEmpty()) return false;
        for (String candidate : candidates) {
            if (candidate != null && packages.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
