package hook.HyperBackscreen.hook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoWallpaperRotationPolicyTest {
    @Test
    public void acceptsSupportedIntervals() {
        assertEquals(0, AutoWallpaperRotationPolicy.parseIntervalMinutes("0"));
        assertEquals(1, AutoWallpaperRotationPolicy.parseIntervalMinutes("1"));
        assertEquals(5, AutoWallpaperRotationPolicy.parseIntervalMinutes("5"));
        assertEquals(15, AutoWallpaperRotationPolicy.parseIntervalMinutes("15"));
    }

    @Test
    public void rejectsUnsupportedIntervals() {
        assertEquals(0, AutoWallpaperRotationPolicy.parseIntervalMinutes(null));
        assertEquals(0, AutoWallpaperRotationPolicy.parseIntervalMinutes("2"));
        assertEquals(0, AutoWallpaperRotationPolicy.parseIntervalMinutes("invalid"));
    }

    @Test
    public void advancesAndWrapsWallpaperIndex() {
        assertEquals(1, AutoWallpaperRotationPolicy.nextIndex(0, 3));
        assertEquals(0, AutoWallpaperRotationPolicy.nextIndex(2, 3));
        assertEquals(-1, AutoWallpaperRotationPolicy.nextIndex(0, 1));
    }

    @Test
    public void calculatesDeadlineFromLastSuccessfulSwitch() {
        long start = 10_000L;
        assertEquals(60_000L,
                AutoWallpaperRotationPolicy.remainingDelayMillis(start, start, 1));
        assertEquals(15_000L,
                AutoWallpaperRotationPolicy.remainingDelayMillis(start, start + 45_000L, 1));
        assertEquals(0L,
                AutoWallpaperRotationPolicy.remainingDelayMillis(start, start + 90_000L, 1));
        assertEquals(Long.MAX_VALUE,
                AutoWallpaperRotationPolicy.remainingDelayMillis(start, start, 0));
    }
}
