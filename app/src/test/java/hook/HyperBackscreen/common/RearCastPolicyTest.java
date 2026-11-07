package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import hook.HyperBackscreen.common.RearCastPolicy.Op;
import hook.HyperBackscreen.common.RearCastPolicy.Outcome;
import hook.HyperBackscreen.common.RearCastPolicy.TaskEntry;

public class RearCastPolicyTest {
    private static final int STANDARD = RearCastPolicy.ACTIVITY_TYPE_STANDARD;
    private static final int HOME = 2;
    private static final int RECENTS = 3;
    private static final int ASSISTANT = 4;
    private static final int FULLSCREEN = RearCastPolicy.WINDOWING_MODE_FULLSCREEN;
    private static final int MULTI_WINDOW = 6;
    private static final int FREEFORM = 5;

    private static TaskEntry task(int id, String pkg, int type, int mode, boolean visible) {
        return new TaskEntry(id, pkg, type, mode, visible);
    }

    // ---- 主屏 → 背屏 ----

    @Test
    public void mainToRearPicksTopmostVisibleStandardApp() {
        TaskEntry wechat = task(14755, "com.tencent.mm", STANDARD, FULLSCREEN, true);
        List<TaskEntry> main = Arrays.asList(
                wechat,
                task(1, Constants.MAIN_LAUNCHER_PACKAGE, HOME, FULLSCREEN, false),
                task(14756, "com.omarea.vtools", STANDARD, FULLSCREEN, false));
        assertSame(wechat, RearCastPolicy.pickMainToRear(main));
    }

    @Test
    public void mainToRearSkipsHomeRecentsAssistantAndInvisibleTasks() {
        List<TaskEntry> main = Arrays.asList(
                task(1, Constants.MAIN_LAUNCHER_PACKAGE, HOME, FULLSCREEN, true),
                task(2, "com.miui.home", RECENTS, FULLSCREEN, true),
                task(3, "com.miui.voiceassist", ASSISTANT, FULLSCREEN, true),
                task(4, "com.tencent.mm", STANDARD, FULLSCREEN, false));
        assertNull("桌面在前台时没有可投放的应用", RearCastPolicy.pickMainToRear(main));
    }

    @Test
    public void mainToRearSkipsExcludedPackagesAndContinuesDown() {
        TaskEntry app = task(9, "com.example.app", STANDARD, FULLSCREEN, true);
        List<TaskEntry> main = Arrays.asList(
                task(5, Constants.SYSTEM_UI_PACKAGE, STANDARD, FULLSCREEN, true),
                task(6, Constants.TARGET_PACKAGE, STANDARD, FULLSCREEN, true),
                task(7, Constants.MAIN_LAUNCHER_PACKAGE, STANDARD, FULLSCREEN, true),
                task(8, null, STANDARD, FULLSCREEN, true),
                app);
        assertSame(app, RearCastPolicy.pickMainToRear(main));
    }

    @Test
    public void mainToRearSkipsSplitScreenFreeformButAcceptsUnknownMode() {
        TaskEntry unknownMode = task(12, "com.example.c", STANDARD, RearCastPolicy.UNKNOWN, true);
        List<TaskEntry> main = Arrays.asList(
                task(10, "com.example.a", STANDARD, MULTI_WINDOW, true),
                task(11, "com.example.b", STANDARD, FREEFORM, true),
                unknownMode);
        assertSame(unknownMode, RearCastPolicy.pickMainToRear(main));
    }

    @Test
    public void mainToRearHandlesEmptyAndNullLists() {
        assertNull(RearCastPolicy.pickMainToRear(null));
        assertNull(RearCastPolicy.pickMainToRear(Collections.emptyList()));
        assertNull(RearCastPolicy.pickMainToRear(Arrays.asList((TaskEntry) null)));
    }

    // ---- 背屏 → 主屏 ----

    @Test
    public void rearToMainIgnoresRearLauncherAndAcceptsHiddenCastApp() {
        // 用户回到背屏桌面后，被投放的应用压在桌面下面、不可见，仍然要能拉回。
        TaskEntry cast = task(14760, "com.tencent.mm", STANDARD, FULLSCREEN, false);
        List<TaskEntry> rear = Arrays.asList(
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, true),
                cast);
        assertSame(cast, RearCastPolicy.pickRearToMain(rear));
    }

    @Test
    public void rearToMainSkipsSubscreenCenterStandardTasks() {
        List<TaskEntry> rear = Arrays.asList(
                task(20, Constants.TARGET_PACKAGE, STANDARD, FULLSCREEN, true),
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, true));
        assertNull("只有背屏中心时背屏上没有投放的应用", RearCastPolicy.pickRearToMain(rear));
    }

    @Test
    public void rearToMainPicksTopmostOfSeveralCastApps() {
        TaskEntry top = task(31, "com.example.top", STANDARD, FULLSCREEN, true);
        List<TaskEntry> rear = Arrays.asList(top,
                task(30, "com.example.below", STANDARD, FULLSCREEN, false),
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, false));
        assertSame(top, RearCastPolicy.pickRearToMain(rear));
        assertNull(RearCastPolicy.pickRearToMain(null));
    }

    // ---- 请求与结果 ----

    @Test
    public void toggleGoesBackToMainOnlyWhenRearHasApp() {
        assertEquals(Op.TO_MAIN, RearCastPolicy.resolveToggle(true));
        assertEquals(Op.TO_REAR, RearCastPolicy.resolveToggle(false));
    }

    @Test
    public void opParsingAcceptsKnownValuesOnly() {
        assertEquals(Op.TO_REAR, Op.fromExtra(Constants.REAR_CAST_OP_TO_REAR));
        assertEquals(Op.TO_MAIN, Op.fromExtra(Constants.REAR_CAST_OP_TO_MAIN));
        assertEquals(Op.TOGGLE, Op.fromExtra(Constants.REAR_CAST_OP_TOGGLE));
        assertEquals(Op.QUERY, Op.fromExtra(Constants.REAR_CAST_OP_QUERY));
        assertNull(Op.fromExtra(null));
        assertNull(Op.fromExtra("TO_REAR"));
        assertNull(Op.fromExtra(""));
    }

    @Test
    public void resultCodesMapToOutcomes() {
        assertEquals(Outcome.MOVED_TO_REAR, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_MOVED_TO_REAR));
        assertEquals(Outcome.MOVED_TO_MAIN, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_MOVED_TO_MAIN));
        assertEquals(Outcome.NO_APP_ON_MAIN, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_NO_APP_ON_MAIN));
        assertEquals(Outcome.NO_APP_ON_REAR, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_NO_APP_ON_REAR));
        assertEquals(Outcome.API_UNAVAILABLE, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_API_UNAVAILABLE));
        assertEquals(Outcome.MOVE_FAILED, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_MOVE_FAILED));
        assertEquals(Outcome.DENIED, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_DENIED));
        assertEquals(Outcome.BAD_REQUEST, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_BAD_REQUEST));
        assertEquals(Outcome.LOCKED, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_LOCKED));
        assertEquals(Outcome.REAR_HAS_APP, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_REAR_HAS_APP));
        assertEquals(Outcome.REAR_EMPTY, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_REAR_EMPTY));
        assertEquals(Outcome.TIMEOUT, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_TIMEOUT));
        assertEquals(Outcome.UNKNOWN, RearCastPolicy.outcomeOf(99));
    }

    @Test
    public void untouchedInitialCodeMeansSystemSideNotActive() {
        // 有序广播没有任何接收器处理时，resultCode 保持发送方给的初始值。
        assertEquals(Outcome.NOT_ACTIVE, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_NOT_HANDLED));
    }

    @Test
    public void resultCodesAreDistinct() {
        int[] codes = {
                Constants.REAR_CAST_RESULT_NOT_HANDLED,
                Constants.REAR_CAST_RESULT_MOVED_TO_REAR,
                Constants.REAR_CAST_RESULT_MOVED_TO_MAIN,
                Constants.REAR_CAST_RESULT_NO_APP_ON_MAIN,
                Constants.REAR_CAST_RESULT_NO_APP_ON_REAR,
                Constants.REAR_CAST_RESULT_API_UNAVAILABLE,
                Constants.REAR_CAST_RESULT_MOVE_FAILED,
                Constants.REAR_CAST_RESULT_DENIED,
                Constants.REAR_CAST_RESULT_BAD_REQUEST,
                Constants.REAR_CAST_RESULT_LOCKED,
                Constants.REAR_CAST_RESULT_REAR_HAS_APP,
                Constants.REAR_CAST_RESULT_REAR_EMPTY,
                Constants.REAR_CAST_RESULT_TIMEOUT,
        };
        assertEquals(codes.length, Arrays.stream(codes).distinct().count());
    }

    @Test
    public void outcomeSuccessAndRearState() {
        assertTrue(Outcome.MOVED_TO_REAR.isSuccess());
        assertTrue(Outcome.MOVED_TO_MAIN.isSuccess());
        assertFalse(Outcome.NO_APP_ON_MAIN.isSuccess());
        assertFalse(Outcome.REAR_HAS_APP.isSuccess());
        assertFalse(Outcome.LOCKED.isSuccess());
        assertNull(Outcome.LOCKED.rearHasApp());

        assertEquals(Boolean.TRUE, Outcome.REAR_HAS_APP.rearHasApp());
        assertEquals(Boolean.TRUE, Outcome.MOVED_TO_REAR.rearHasApp());
        assertEquals(Boolean.FALSE, Outcome.REAR_EMPTY.rearHasApp());
        assertEquals(Boolean.FALSE, Outcome.NO_APP_ON_REAR.rearHasApp());
        // 拉回后背屏上可能还有别的投放应用，必须重新查询。
        assertNull(Outcome.MOVED_TO_MAIN.rearHasApp());
        assertNull(Outcome.NOT_ACTIVE.rearHasApp());
        assertNull(Outcome.TIMEOUT.rearHasApp());
    }

    @Test
    public void onlySubscreenCenterAndModuleMaySend() {
        assertTrue(RearCastPolicy.isAllowedSender(Constants.TARGET_PACKAGE));
        assertTrue(RearCastPolicy.isAllowedSender(Constants.MODULE_PACKAGE));
        assertFalse(RearCastPolicy.isAllowedSender(null));
        assertFalse(RearCastPolicy.isAllowedSender(""));
        assertFalse(RearCastPolicy.isAllowedSender("com.example.evil"));
        assertFalse(RearCastPolicy.isAllowedSender(Constants.SYSTEM_UI_PACKAGE));
    }

    @Test
    public void containsTaskFindsMovedTask() {
        List<TaskEntry> rear = Arrays.asList(
                task(41, "com.example.app", STANDARD, FULLSCREEN, true),
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, false));
        assertTrue(RearCastPolicy.containsTask(rear, 41));
        assertFalse(RearCastPolicy.containsTask(rear, 42));
        assertFalse(RearCastPolicy.containsTask(null, 41));
    }
}
