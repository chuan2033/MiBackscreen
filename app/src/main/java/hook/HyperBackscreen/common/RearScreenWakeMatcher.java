package hook.HyperBackscreen.common;

import androidx.annotation.Nullable;

import java.util.Set;

public final class RearScreenWakeMatcher {
    private static final int REAR_SCREEN_DISPLAY_GROUP_ID = 1;

    private RearScreenWakeMatcher() {
    }

    public static boolean isRearDoubleTapWake(int groupId, @Nullable Object details) {
        // Verified in the device's DualScreenCoverManager: KEY is the rear double-tap event.
        return groupId == REAR_SCREEN_DISPLAY_GROUP_ID
                && Constants.WAKE_REASON_DOUBLE_TAP.equals(details);
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
