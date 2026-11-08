package hook.HyperBackscreen.common;

/** Report rejected/missing activity handlers without hiding success from the caller. */
public final class ActivityLaunch {
    private ActivityLaunch() {}

    public static boolean attempt(Runnable launch) {
        try {
            launch.run();
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
