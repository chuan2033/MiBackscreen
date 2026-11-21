package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 背屏桌面的纯逻辑：密度档位、会话现场的编码与恢复计划，模块 App 与 system_server 共用。
 *
 * <p>背屏 976×596、450 dpi，左侧 296 px 留给摄像头，应用可用区域只有约 241×212 dp，
 * 多数应用按 320 dp 以上设计。会话期间把背屏密度调低，让应用拿到更宽的布局；会话结束恢复原值。
 */
public final class RearDesktopPolicy {
    /** 不调整背屏密度。 */
    public static final int DENSITY_UNCHANGED = 0;
    /** 可选档位，按应用可用宽度从窄到宽排列；0 表示不调整。 */
    public static final int[] DENSITY_OPTIONS = {DENSITY_UNCHANGED, 320, 280, 240};
    public static final int DEFAULT_DENSITY = 280;
    /** 会话现场里「会话开始前没有强制密度」的取值。 */
    public static final int NO_FORCED_DENSITY = -1;
    /** 旧版现场没有记录用户。 */
    public static final int USER_UNKNOWN = -10_000;
    /** UserHandle.USER_SYSTEM。 */
    public static final int USER_SYSTEM = 0;
    private static final String FORMAT_V2 = "2";

    private RearDesktopPolicy() {
    }

    /** 偏好值 → dpi；缺失或不认识的值按默认档位。 */
    public static int parseDensity(@Nullable String value) {
        Integer parsed = matchOption(value);
        return parsed == null ? DEFAULT_DENSITY : parsed;
    }

    /** 备份恢复时校验：合法档位返回规范字符串，否则 null。 */
    @Nullable
    public static String normalizeDensity(@Nullable String value) {
        Integer parsed = matchOption(value);
        return parsed == null ? null : String.valueOf(parsed);
    }

    @Nullable
    private static Integer matchOption(@Nullable String value) {
        if (value == null) return null;
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
        for (int option : DENSITY_OPTIONS) {
            if (option == parsed) return parsed;
        }
        return null;
    }

    /** 只接受档位里的值（防止极端值把背屏弄得不可用）。 */
    public static boolean isAcceptedDensity(int density) {
        for (int option : DENSITY_OPTIONS) {
            if (option == density) return true;
        }
        return false;
    }

    /**
     * 会话开始前背屏的设置，会话结束或开机时据此恢复。
     *
     * <p>当前格式 {@code 2:<用户>:<强制密度>:<是否忽略方向请求>:<会话是否改过密度>}，例如 {@code 2:0:-1:0:1}；
     * 旧版格式 {@code <强制密度>:<是否忽略方向请求>} 没有用户信息，读取为 {@link #USER_UNKNOWN} 且视为改过密度，
     * 旧版记录部分恢复后按当前格式写回，用户仍为 {@link #USER_UNKNOWN}。
     */
    public static final class RestoreState {
        public final int userId;
        /** 会话开始前的强制密度；{@link #NO_FORCED_DENSITY} 表示当时没有强制密度。 */
        public final int forcedDensity;
        public final boolean ignoreOrientationRequest;
        /** 会话改过该用户的密度；档位 0 的会话为 false，恢复时保留原密度。 */
        public final boolean densityApplied;

        public RestoreState(int userId, int forcedDensity, boolean ignoreOrientationRequest, boolean densityApplied) {
            this.userId = userId;
            this.forcedDensity = forcedDensity;
            this.ignoreOrientationRequest = ignoreOrientationRequest;
            this.densityApplied = densityApplied;
        }

        public boolean isLegacy() {
            return userId == USER_UNKNOWN;
        }

        @NonNull
        public RestoreState withDensityApplied(boolean applied) {
            return new RestoreState(userId, forcedDensity, ignoreOrientationRequest, applied);
        }

        /** 总是写当前格式；旧版记录改写后用户仍记为 {@link #USER_UNKNOWN}。 */
        @NonNull
        public String encode() {
            return FORMAT_V2 + ":" + userId + ":" + forcedDensity + ":" + (ignoreOrientationRequest ? 1 : 0)
                    + ":" + (densityApplied ? 1 : 0);
        }

        /** 解析失败返回 null（按没有需要恢复的现场处理）。 */
        @Nullable
        public static RestoreState decode(@Nullable String value) {
            if (value == null) return null;
            String[] parts = value.trim().split(":");
            try {
                if (parts.length == 2) {
                    int density = Integer.parseInt(parts[0]);
                    Boolean ignore = flag(parts[1]);
                    if (!validDensity(density) || ignore == null) return null;
                    return new RestoreState(USER_UNKNOWN, density, ignore, true);
                }
                if (parts.length == 5 && FORMAT_V2.equals(parts[0])) {
                    int user = Integer.parseInt(parts[1]);
                    int density = Integer.parseInt(parts[2]);
                    Boolean ignore = flag(parts[3]);
                    Boolean applied = flag(parts[4]);
                    if ((user < 0 && user != USER_UNKNOWN) || !validDensity(density)
                            || ignore == null || applied == null) {
                        return null;
                    }
                    return new RestoreState(user, density, ignore, applied);
                }
            } catch (NumberFormatException error) {
                return null;
            }
            return null;
        }

        private static boolean validDensity(int density) {
            return density == NO_FORCED_DENSITY || density > 0;
        }

        @Nullable
        private static Boolean flag(@NonNull String value) {
            if ("1".equals(value)) return Boolean.TRUE;
            if ("0".equals(value)) return Boolean.FALSE;
            return null;
        }
    }

    /** 从基础密度与初始密度判断会话开始前是否已有强制密度。 */
    public static int forcedDensityOf(int baseDensity, int initialDensity) {
        return baseDensity > 0 && baseDensity != initialDensity ? baseDensity : NO_FORCED_DENSITY;
    }

    /** 会话开始时是否改密度：档位 0 保留原密度。 */
    public static boolean shouldApplyDensity(int density) {
        return density != DENSITY_UNCHANGED && isAcceptedDensity(density);
    }

    /** 一次恢复要做的事。 */
    public static final class RestorePlan {
        public final boolean restoreDensity;
        /** 恢复密度时写入的用户。 */
        public final int densityUserId;
        public final int forcedDensity;
        public final boolean ignoreOrientationRequest;

        RestorePlan(boolean restoreDensity, int densityUserId, int forcedDensity, boolean ignoreOrientationRequest) {
            this.restoreDensity = restoreDensity;
            this.densityUserId = densityUserId;
            this.forcedDensity = forcedDensity;
            this.ignoreOrientationRequest = ignoreOrientationRequest;
        }
    }

    /**
     * 恢复计划。方向设置属于整块背屏，总是恢复。密度只写回记录里的用户；旧版记录没有用户，
     * 只在设备只有系统用户时写回系统用户（旧版会话只可能属于它），否则跳过，
     * 不把其他用户的强制密度写进当前用户。
     */
    @NonNull
    public static RestorePlan planRestore(@NonNull RestoreState state, boolean onlySystemUser) {
        if (!state.densityApplied) {
            return new RestorePlan(false, state.userId, state.forcedDensity, state.ignoreOrientationRequest);
        }
        if (state.isLegacy()) {
            return new RestorePlan(onlySystemUser, USER_SYSTEM, state.forcedDensity, state.ignoreOrientationRequest);
        }
        return new RestorePlan(true, state.userId, state.forcedDensity, state.ignoreOrientationRequest);
    }

    /**
     * 执行计划后还需要保留的记录：两项都完成返回 null（清除记录）；有失败时保留记录，
     * 已经写回的密度不再重复，方向设置下次照常恢复。
     */
    @Nullable
    public static RestoreState remainingAfter(@NonNull RestoreState state, @NonNull RestorePlan plan,
                                              boolean densityRestored, boolean orientationRestored) {
        boolean densityPending = plan.restoreDensity && !densityRestored;
        if (!densityPending && orientationRestored) return null;
        return state.withDensityApplied(densityPending);
    }
}
