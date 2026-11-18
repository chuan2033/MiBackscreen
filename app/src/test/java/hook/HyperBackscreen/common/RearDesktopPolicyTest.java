package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RearDesktopPolicyTest {
    @Test
    public void parseDensityFallsBackToDefault() {
        assertEquals(280, RearDesktopPolicy.parseDensity("280"));
        assertEquals(240, RearDesktopPolicy.parseDensity(" 240 "));
        assertEquals(RearDesktopPolicy.DENSITY_UNCHANGED, RearDesktopPolicy.parseDensity("0"));
        assertEquals(RearDesktopPolicy.DEFAULT_DENSITY, RearDesktopPolicy.parseDensity("100"));
        assertEquals(RearDesktopPolicy.DEFAULT_DENSITY, RearDesktopPolicy.parseDensity("abc"));
        assertEquals(RearDesktopPolicy.DEFAULT_DENSITY, RearDesktopPolicy.parseDensity(null));
    }

    @Test
    public void normalizeDensityRejectsUnknownValues() {
        assertEquals("320", RearDesktopPolicy.normalizeDensity("320"));
        assertEquals("0", RearDesktopPolicy.normalizeDensity("0"));
        assertNull(RearDesktopPolicy.normalizeDensity("450"));
        assertNull(RearDesktopPolicy.normalizeDensity(""));
        assertNull(RearDesktopPolicy.normalizeDensity(null));
    }

    @Test
    public void acceptedDensityMatchesOptions() {
        for (int option : RearDesktopPolicy.DENSITY_OPTIONS) {
            assertTrue(RearDesktopPolicy.isAcceptedDensity(option));
        }
        assertFalse(RearDesktopPolicy.isAcceptedDensity(-1));
        assertFalse(RearDesktopPolicy.isAcceptedDensity(1));
        assertFalse(RearDesktopPolicy.isAcceptedDensity(450));
    }

    @Test
    public void restoreStateRoundTrips() {
        RearDesktopPolicy.RestoreState none = new RearDesktopPolicy.RestoreState(RearDesktopPolicy.NO_FORCED_DENSITY, false);
        assertEquals("-1:0", none.encode());
        RearDesktopPolicy.RestoreState decoded = RearDesktopPolicy.RestoreState.decode(none.encode());
        assertEquals(RearDesktopPolicy.NO_FORCED_DENSITY, decoded.forcedDensity);
        assertFalse(decoded.ignoreOrientationRequest);

        RearDesktopPolicy.RestoreState forced = RearDesktopPolicy.RestoreState.decode("360:1");
        assertEquals(360, forced.forcedDensity);
        assertTrue(forced.ignoreOrientationRequest);
    }

    @Test
    public void restoreStateRejectsMalformedValues() {
        assertNull(RearDesktopPolicy.RestoreState.decode(null));
        assertNull(RearDesktopPolicy.RestoreState.decode(""));
        assertNull(RearDesktopPolicy.RestoreState.decode("360"));
        assertNull(RearDesktopPolicy.RestoreState.decode("360:2"));
        assertNull(RearDesktopPolicy.RestoreState.decode("0:0"));
        assertNull(RearDesktopPolicy.RestoreState.decode("a:b"));
    }

    @Test
    public void forcedDensityOnlyWhenBaseDiffersFromInitial() {
        assertEquals(RearDesktopPolicy.NO_FORCED_DENSITY, RearDesktopPolicy.forcedDensityOf(450, 450));
        assertEquals(360, RearDesktopPolicy.forcedDensityOf(360, 450));
        assertEquals(RearDesktopPolicy.NO_FORCED_DENSITY, RearDesktopPolicy.forcedDensityOf(0, 450));
    }
}
