package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RearScreenScheduleTest {
    @Test public void parsesHourMinuteToMinutesOfDay() {
        assertEquals(0, RearScreenSchedule.parseMinutes("00:00", -1));
        assertEquals(450, RearScreenSchedule.parseMinutes("07:30", -1));
        assertEquals(1380, RearScreenSchedule.parseMinutes("23:00", -1));
        assertEquals(1439, RearScreenSchedule.parseMinutes("23:59", -1));
    }

    @Test public void rejectsMalformedTime() {
        assertEquals(-1, RearScreenSchedule.parseMinutes(null, -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("", -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("7:5", -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("7:05", -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("24:00", -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("23:60", -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("2300", -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("23:00:", -1));
        assertEquals(-1, RearScreenSchedule.parseMinutes("xx:yy", -1));
    }

    @Test public void formatsWithTwoDigits() {
        assertEquals("00:00", RearScreenSchedule.format(0));
        assertEquals("07:05", RearScreenSchedule.format(7 * 60 + 5));
        assertEquals("23:59", RearScreenSchedule.format(1439));
        // 越界值回落到 0 点，不让畸形值生成不可解析的串。
        assertEquals("00:00", RearScreenSchedule.format(-1));
        assertEquals("00:00", RearScreenSchedule.format(1440));
    }

    @Test public void normalizeFallsBackToDefault() {
        assertEquals("23:00", RearScreenSchedule.normalize(null, "23:00"));
        assertEquals("23:00", RearScreenSchedule.normalize("25:00", "23:00"));
        assertEquals("07:00", RearScreenSchedule.normalize("7:00", "07:00"));
    }

    @Test public void sameDayWindowIsActiveBetweenStartAndEnd() {
        String start = "08:00";
        String end = "12:00";
        assertFalse(RearScreenSchedule.isActive(start, end, 7 * 60 + 59));
        assertTrue(RearScreenSchedule.isActive(start, end, 8 * 60));
        assertTrue(RearScreenSchedule.isActive(start, end, 11 * 60 + 59));
        // 右开区间：到点即恢复。
        assertFalse(RearScreenSchedule.isActive(start, end, 12 * 60));
    }

    @Test public void overnightWindowSpansMidnight() {
        String start = "23:00";
        String end = "07:00";
        assertFalse(RearScreenSchedule.isActive(start, end, 12 * 60));
        assertFalse(RearScreenSchedule.isActive(start, end, 22 * 60 + 59));
        assertTrue(RearScreenSchedule.isActive(start, end, 23 * 60));
        assertTrue(RearScreenSchedule.isActive(start, end, 0));
        assertTrue(RearScreenSchedule.isActive(start, end, 6 * 60 + 59));
        assertFalse(RearScreenSchedule.isActive(start, end, 7 * 60));
    }

    @Test public void emptyOrInvalidWindowNeverActivates() {
        assertFalse(RearScreenSchedule.isActive("23:00", "23:00", 23 * 60 + 30));
        assertFalse(RearScreenSchedule.isActive(null, "07:00", 0));
        assertFalse(RearScreenSchedule.isActive("23:00", "oops", 0));
    }

    @Test public void wakeSlackOnlyAroundEndBoundary() {
        String start = "23:00";
        String end = "07:00";
        // 结束边界附近的宽限内才主动点亮。
        assertTrue(RearScreenSchedule.isWithinWakeSlack(start, end, 7 * 60));
        assertTrue(RearScreenSchedule.isWithinWakeSlack(start, end, 7 * 60 + 3));
        assertFalse(RearScreenSchedule.isWithinWakeSlack(start, end, 7 * 60 + 4));
        // 手机休眠几小时后才醒来，不点亮。
        assertFalse(RearScreenSchedule.isWithinWakeSlack(start, end, 10 * 60));
        // 窗口内也不点亮。
        assertFalse(RearScreenSchedule.isWithinWakeSlack(start, end, 23 * 60 + 30));
        assertFalse(RearScreenSchedule.isWithinWakeSlack(start, end, 0));
    }

    @Test public void wakeSlackDisabledForEmptyWindow() {
        assertFalse(RearScreenSchedule.isWithinWakeSlack("23:00", "23:00", 23 * 60));
        assertFalse(RearScreenSchedule.isWithinWakeSlack("23:00", null, 23 * 60));
    }
}
