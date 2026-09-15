package hook.HyperBackscreen.common;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class PickupCodesTest {
    @Test public void preservesAllCodesAndLeadingZeros() {
        assertEquals(Arrays.asList("9-3-5016", "7-5-5233", "7-3-6026", "9-3-1015", "0058"),
                PickupCodes.parse("9-3-5016,7-5-5233,7-3-6026,9-3-1015,0058"));
    }

    @Test public void onlyDeliveryWithMultipleCodesGetsEntry() {
        assertTrue(PickupCodes.shouldAddEntry("delivery", "A01,002"));
        for (String scene : Arrays.asList(null, "", "pickup_food", "pickup_drink", "locker",
                "queue", "calendar", "music", "DELIVERY")) {
            assertFalse(PickupCodes.shouldAddEntry(scene, "A01,002"));
        }
        assertFalse(PickupCodes.shouldAddEntry("delivery", "001"));
        assertFalse(PickupCodes.shouldAddEntry("delivery", "001,001"));
    }

    @Test public void separatorsAndDuplicatesPreserveOrder() {
        assertEquals(Arrays.asList("001", "A02", "3-04", "B05"),
                PickupCodes.parse(" 001\uFF0CA02;3-04\nB05\u3001001 "));
    }

    @Test public void invalidOrTruncatedPayloadDoesNotPartiallyOverrideCard() {
        for (String title : Arrays.asList(null, "", "A01,...", "A01,", "A01,https://x",
                "A01,ABC", "A01,1--2", "A01,2\u202E3", "A01,2 3", "A".repeat(4097))) {
            assertEquals(Collections.emptyList(), PickupCodes.parse(title));
            assertFalse(PickupCodes.shouldAddEntry("delivery", title));
        }
    }

    @Test public void capsExternalData() {
        assertTrue(PickupCodes.parse(String.join(",", Collections.nCopies(65, "001"))).isEmpty());
        assertTrue(PickupCodes.parse("1".repeat(33)).isEmpty());
        assertEquals(512, PickupCodes.station("A".repeat(800)).length());
        assertEquals("", PickupCodes.station(null));
    }

    @Test public void islandSelectionKeepsOnlyCheckedCodesInOriginalOrder() {
        List<String> codes = Arrays.asList("9-3-5016", "7-5-5233", "0058");
        String identity = PickupCodes.identity(codes, "A站");
        String stored = PickupCodes.encodeIslandSelection(identity, Arrays.asList("0058", "9-3-5016"));
        assertEquals(Arrays.asList("9-3-5016", "0058"),
                PickupCodes.applyIslandSelection(codes, identity, stored));
    }

    @Test public void islandSelectionFallsBackToShowingAllWhenMissing() {
        List<String> codes = Arrays.asList("001", "002");
        String identity = PickupCodes.identity(codes, "");
        assertEquals(codes, PickupCodes.applyIslandSelection(codes, identity, null));
        assertEquals(codes, PickupCodes.applyIslandSelection(codes, identity, ""));
        // 选择属于另一次识别时不生效
        assertEquals(codes, PickupCodes.applyIslandSelection(codes, identity,
                PickupCodes.encodeIslandSelection("deadbeef", Arrays.asList("001"))));
    }

    @Test public void explicitEmptySelectionHidesAllCodes() {
        List<String> codes = Arrays.asList("001", "002");
        String identity = PickupCodes.identity(codes, "");
        assertTrue(PickupCodes.applyIslandSelection(
                codes, identity, PickupCodes.encodeHiddenIslandSelection(identity)).isEmpty());
    }

    @Test public void emptyStationUsesCodeIdentity() {
        List<String> codes = Arrays.asList("001", "002");
        assertEquals(PickupCodes.identity(codes, ""), PickupCodes.displayIdentity(codes, ""));
    }

    @Test public void malformedSelectionIsIgnored() {
        List<String> codes = Arrays.asList("001", "002");
        assertEquals(codes, PickupCodes.applyIslandSelection(
                codes, PickupCodes.identity(codes, ""), "bad|001"));
    }

    @Test public void identityIsStableAndDependsOnStation() {
        List<String> codes = Collections.singletonList("001");
        assertEquals(PickupCodes.identity(codes, "A"), PickupCodes.identity(codes, "A"));
        assertNotEquals(PickupCodes.identity(codes, "A"), PickupCodes.identity(codes, "B"));
        assertNotEquals(PickupCodes.stationIdentity("A"), PickupCodes.stationIdentity("B"));
    }

    @Test public void islandTextLaysOutTwoPerRowAndCapsAtFour() {
        List<String> six = Arrays.asList("a1", "a2", "a3", "a4", "a5", "a6");
        String text = PickupCodes.formatIslandText(six);
        assertEquals(PickupCodes.ISLAND_MAX_ROWS, text.split("\n", -1).length);
        assertEquals(2, text.split("\n", -1)[0].split("\\s+").length);
        assertTrue(text.contains("a4"));
        assertFalse(text.contains("a5"));
        assertFalse(text.contains("a6"));
        assertFalse(PickupCodes.formatIslandText(Arrays.asList("001", "002")).contains("\n"));
        assertEquals("取件码", PickupCodes.formatIslandText(Collections.emptyList()));
        assertEquals("取件码", PickupCodes.formatCollapsedText(Collections.emptyList()));
    }

    @Test public void mergeDistinctKeepsOrderAndSkipsDuplicates() {
        assertEquals(Arrays.asList("a", "b", "c"),
                PickupCodes.mergeDistinct(Arrays.asList("a", "b"), Arrays.asList("b", "c")));
        assertEquals(Arrays.asList("a"),
                PickupCodes.mergeDistinct(Arrays.asList("a"), Collections.emptyList()));
    }

    @Test public void collapsedTitleFollowsSelectionWithoutLosingOtherCodes() {
        List<String> codes = Arrays.asList("001", "002", "003", "004", "005");
        String identity = PickupCodes.identity(codes, "A");
        List<String> selected = PickupCodes.applyIslandSelection(codes, identity,
                PickupCodes.encodeIslandSelection(identity, Collections.singletonList("005")));
        assertEquals("005", PickupCodes.formatCollapsedText(selected));
        assertEquals("001,002,003,004", PickupCodes.formatCollapsedText(codes));
        assertEquals(5, codes.size());
    }

    @Test public void stationSelectionsSurviveMultiplePickupGroups() {
        String first = PickupCodes.stationIdentity("A站");
        String second = PickupCodes.stationIdentity("B站");
        String stored = PickupCodes.upsertIslandSelection("", first,
                Collections.singletonList("001"), false);
        stored = PickupCodes.upsertIslandSelection(stored, second,
                Collections.singletonList("902"), false);
        assertEquals(Arrays.asList("001"), PickupCodes.applyIslandSelection(
                Arrays.asList("001", "002"), first, stored));
        assertEquals(Arrays.asList("902"), PickupCodes.applyIslandSelection(
                Arrays.asList("901", "902"), second, stored));
        stored = PickupCodes.upsertIslandSelection(stored, first,
                Arrays.asList("001", "002"), true);
        assertEquals(Arrays.asList("902"), PickupCodes.applyIslandSelection(
                Arrays.asList("901", "902"), second, stored));
    }
}
