package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RearShellLayoutTest {
    @Test
    public void officialHalfShellMetricsProduceExpectedCanvasAndContentRect() {
        RearShellLayout layout = new RearShellLayout(
                976f,
                596f,
                125f,
                926f,
                113f,
                1108f);

        RearShellLayout.Rect rect = layout.contentRectFor(976, 596);

        assertEquals(1214, layout.shellWidth());
        assertEquals(2630, layout.shellHeight());
        assertEquals(125f, rect.left, 0.001f);
        assertEquals(926f, rect.top, 0.001f);
        assertEquals(1101f, rect.right, 0.001f);
        assertEquals(1522f, rect.bottom, 0.001f);
    }

    @Test
    public void shorterCaptureAddsOfficialTopBlackCompensation() {
        RearShellLayout layout = new RearShellLayout(
                976f,
                596f,
                125f,
                926f,
                113f,
                1108f);

        RearShellLayout.Rect rect = layout.contentRectFor(976, 488);

        assertEquals(108f, layout.topBlackHeightFor(976, 488), 0.001f);
        assertEquals(1034f, rect.top, 0.001f);
        assertEquals(1522f, rect.bottom, 0.001f);
    }
}
