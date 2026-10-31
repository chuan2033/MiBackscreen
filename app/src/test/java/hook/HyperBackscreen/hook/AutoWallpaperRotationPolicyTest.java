package hook.HyperBackscreen.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
    public void fallsBackToSequentialForUnknownOrder() {
        assertEquals(AutoWallpaperRotationPolicy.ORDER_SEQUENTIAL,
                AutoWallpaperRotationPolicy.normalizeOrder(null));
        assertEquals(AutoWallpaperRotationPolicy.ORDER_SEQUENTIAL,
                AutoWallpaperRotationPolicy.normalizeOrder("whatever"));
        assertEquals(AutoWallpaperRotationPolicy.ORDER_RANDOM,
                AutoWallpaperRotationPolicy.normalizeOrder("random"));
    }

    @Test
    public void randomOrderNeverRepeatsCurrentIndex() {
        java.util.Random random = new java.util.Random(42L);
        for (int round = 0; round < 200; round++) {
            for (int current = 0; current < 4; current++) {
                int next = AutoWallpaperRotationPolicy.nextIndex(
                        current, 4, AutoWallpaperRotationPolicy.ORDER_RANDOM, random);
                assertTrue("next index out of range: " + next, next >= 0 && next < 4);
                assertTrue("random repeated current index", next != current);
            }
        }
    }

    @Test
    public void randomOrderCoversEveryOtherIndex() {
        java.util.Random random = new java.util.Random(7L);
        boolean[] seen = new boolean[3];
        for (int round = 0; round < 300; round++) {
            int next = AutoWallpaperRotationPolicy.nextIndex(
                    1, 3, AutoWallpaperRotationPolicy.ORDER_RANDOM, random);
            seen[next] = true;
        }
        // current=1 时另外两张（0、2）都应该被抽到过。
        assertTrue(seen[0]);
        assertTrue(seen[2]);
    }

    @Test
    public void sequentialOrderIgnoresRandomSource() {
        java.util.Random random = new java.util.Random(1L);
        assertEquals(2, AutoWallpaperRotationPolicy.nextIndex(
                1, 3, AutoWallpaperRotationPolicy.ORDER_SEQUENTIAL, random));
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
