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
        assertEquals(Op.PANEL_LAUNCH, Op.fromExtra(Constants.REAR_CAST_OP_PANEL_LAUNCH));
        assertEquals(Op.PANEL_QUERY, Op.fromExtra(Constants.REAR_CAST_OP_PANEL_QUERY));
        assertEquals(Op.PANEL_BACK, Op.fromExtra(Constants.REAR_CAST_OP_PANEL_BACK));
        assertEquals(Op.PANEL_HOME, Op.fromExtra(Constants.REAR_CAST_OP_PANEL_HOME));
        assertEquals(Op.PANEL_TO_MAIN, Op.fromExtra(Constants.REAR_CAST_OP_PANEL_TO_MAIN));
        assertEquals(Op.PANEL_EXIT, Op.fromExtra(Constants.REAR_CAST_OP_PANEL_EXIT));
        assertEquals(Op.DENSITY_REFRESH, Op.fromExtra(Constants.REAR_CAST_OP_DENSITY_REFRESH));
        assertNull(Op.fromExtra("desktop_open"));
        assertNull(Op.fromExtra("back"));
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
        assertEquals(Outcome.DONE, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_DONE));
        assertEquals(Outcome.ACCEPTED, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_ACCEPTED));
        assertEquals(Outcome.ROM_DENIED, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_ROM_DENIED));
        assertEquals(Outcome.BAD_COMPONENT, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_BAD_COMPONENT));
        assertEquals(Outcome.NO_SESSION, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_NO_SESSION));
        assertEquals(Outcome.BUSY, RearCastPolicy.outcomeOf(Constants.REAR_CAST_RESULT_BUSY));
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
                Constants.REAR_CAST_RESULT_DONE,
                Constants.REAR_CAST_RESULT_ACCEPTED,
                Constants.REAR_CAST_RESULT_ROM_DENIED,
                Constants.REAR_CAST_RESULT_BAD_COMPONENT,
                Constants.REAR_CAST_RESULT_NO_SESSION,
                Constants.REAR_CAST_RESULT_BUSY,
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
    public void panelOpsAcceptOnlySubscreenCenter() {
        for (Op op : new Op[]{Op.PANEL_LAUNCH, Op.PANEL_QUERY, Op.PANEL_BACK, Op.PANEL_HOME,
                Op.PANEL_TO_MAIN, Op.PANEL_EXIT}) {
            assertTrue(op.isPanelOp());
            assertTrue(op.isDesktopOp());
            assertTrue(RearCastPolicy.isAllowedSenderFor(op, Constants.TARGET_PACKAGE));
            assertFalse(op + " from module", RearCastPolicy.isAllowedSenderFor(op, Constants.MODULE_PACKAGE));
            assertFalse(RearCastPolicy.isAllowedSenderFor(op, null));
            assertFalse(RearCastPolicy.isAllowedSenderFor(op, "com.example.evil"));
        }
    }

    @Test
    public void densityRefreshAcceptsOnlyModule() {
        assertTrue(Op.DENSITY_REFRESH.isDesktopOp());
        assertFalse(Op.DENSITY_REFRESH.isPanelOp());
        assertTrue(RearCastPolicy.isAllowedSenderFor(Op.DENSITY_REFRESH, Constants.MODULE_PACKAGE));
        assertFalse(RearCastPolicy.isAllowedSenderFor(Op.DENSITY_REFRESH, Constants.TARGET_PACKAGE));
        assertFalse(RearCastPolicy.isAllowedSenderFor(Op.DENSITY_REFRESH, null));
    }

    @Test
    public void regularCastOpsKeepChannelSenders() {
        for (Op op : new Op[]{Op.TO_REAR, Op.TO_MAIN, Op.TOGGLE, Op.QUERY}) {
            assertFalse(op.isDesktopOp());
            assertTrue(RearCastPolicy.isAllowedSenderFor(op, Constants.TARGET_PACKAGE));
            assertTrue(RearCastPolicy.isAllowedSenderFor(op, Constants.MODULE_PACKAGE));
            assertFalse(RearCastPolicy.isAllowedSenderFor(op, Constants.SYSTEM_UI_PACKAGE));
        }
    }

    @Test
    public void sessionControlOps() {
        assertTrue(Op.PANEL_BACK.isSessionControl());
        assertFalse(Op.PANEL_BACK.endsSession());
        assertTrue(Op.PANEL_HOME.endsSession());
        assertTrue(Op.PANEL_TO_MAIN.endsSession());
        assertTrue(Op.PANEL_EXIT.endsSession());
        assertFalse(Op.PANEL_LAUNCH.isSessionControl());
        assertFalse(Op.PANEL_QUERY.isSessionControl());
    }

    // ---- 身份 ----

    @Test
    public void senderIdentityRequiresExactUidInCurrentUnlockedUser() {
        int owner = 10_123;
        int secondary = 10 * RearCastPolicy.PER_USER_UID_RANGE + 10_123;
        assertTrue(RearCastPolicy.isSenderIdentityAccepted(owner, owner, 0, true));
        // 同一应用在其他用户：uid 不同，且不是当前用户。
        assertFalse(RearCastPolicy.isSenderIdentityAccepted(secondary, owner, 0, true));
        assertFalse("包名在发送者用户下解析出的 uid 与发送方不同",
                RearCastPolicy.isSenderIdentityAccepted(owner, 10_124, 0, true));
        assertTrue(RearCastPolicy.isSenderIdentityAccepted(secondary, secondary, 10, true));
        assertFalse("发送者不是当前用户", RearCastPolicy.isSenderIdentityAccepted(secondary, secondary, 0, true));
        assertFalse("用户未解锁", RearCastPolicy.isSenderIdentityAccepted(owner, owner, 0, false));
        assertFalse("缺失身份", RearCastPolicy.isSenderIdentityAccepted(-1, owner, 0, true));
        assertFalse("包名解析失败", RearCastPolicy.isSenderIdentityAccepted(owner, -1, 0, true));
        assertEquals(10, RearCastPolicy.userIdOf(secondary));
        assertEquals(RearCastPolicy.UNKNOWN, RearCastPolicy.userIdOf(-1));
    }

    // ---- 组件规则 ----

    private static RearCastPolicy.LaunchTarget target(String pkg, boolean exported, boolean enabled,
                                                      boolean launcher, String permission, boolean holds) {
        return new RearCastPolicy.LaunchTarget(true, pkg, exported, enabled, launcher, permission, holds);
    }

    @Test
    public void launchableRequiresExportedEnabledLauncherEntry() {
        assertTrue(RearCastPolicy.isLaunchable(target("com.example.app", true, true, true, null, false)));
        assertFalse(RearCastPolicy.isLaunchable(target("com.example.app", false, true, true, null, false)));
        assertFalse(RearCastPolicy.isLaunchable(target("com.example.app", true, false, true, null, false)));
        assertFalse("不是桌面入口", RearCastPolicy.isLaunchable(target("com.example.app", true, true, false, null, false)));
        assertFalse(RearCastPolicy.isLaunchable(null));
        assertFalse("未解析到", RearCastPolicy.isLaunchable(
                new RearCastPolicy.LaunchTarget(false, "com.example.app", true, true, true, null, false)));
    }

    @Test
    public void launchableChecksPermissionAndExclusions() {
        assertTrue(RearCastPolicy.isLaunchable(target("com.example.app", true, true, true, "p.X", true)));
        assertFalse(RearCastPolicy.isLaunchable(target("com.example.app", true, true, true, "p.X", false)));
        for (String excluded : new String[]{Constants.MODULE_PACKAGE, Constants.TARGET_PACKAGE,
                Constants.SYSTEM_UI_PACKAGE, Constants.MAIN_LAUNCHER_PACKAGE}) {
            assertFalse(excluded, RearCastPolicy.isLaunchable(target(excluded, true, true, true, null, false)));
        }
    }

    // ---- 会话任务 ----

    @Test
    public void sessionTaskLookupIgnoresRegularCastTarget() {
        // 常规投放查询会挑中背屏最上层的应用（51），会话控制只认绑定的任务（41）。
        List<TaskEntry> rear = Arrays.asList(
                task(51, "com.example.other", STANDARD, FULLSCREEN, true),
                task(41, "com.example.session", STANDARD, FULLSCREEN, false),
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, false));
        assertEquals(51, RearCastPolicy.pickRearToMain(rear).rootTaskId);
        assertEquals(41, RearCastPolicy.findTask(rear, 41).rootTaskId);
        assertNull(RearCastPolicy.findTask(rear, 42));
        assertNull(RearCastPolicy.findTask(rear, RearCastPolicy.UNKNOWN));
        assertEquals(RearCastPolicy.Presence.HIDDEN, RearCastPolicy.presenceOf(rear, null, 41));
    }

    @Test
    public void presenceClassification() {
        List<TaskEntry> visible = Arrays.asList(
                task(41, "com.example.app", STANDARD, FULLSCREEN, true),
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, false));
        assertEquals(RearCastPolicy.Presence.VISIBLE, RearCastPolicy.presenceOf(visible, null, 41));

        List<TaskEntry> homeOnTop = Arrays.asList(
                task(14757, Constants.TARGET_PACKAGE, STANDARD, FULLSCREEN, true),
                task(41, "com.example.app", STANDARD, FULLSCREEN, false));
        assertEquals(RearCastPolicy.Presence.HOME_ON_TOP, RearCastPolicy.presenceOf(homeOnTop, null, 41));

        List<TaskEntry> nothingVisible = Arrays.asList(
                task(41, "com.example.app", STANDARD, FULLSCREEN, false));
        assertEquals(RearCastPolicy.Presence.HIDDEN, RearCastPolicy.presenceOf(nothingVisible, null, 41));

        List<TaskEntry> main = Arrays.asList(task(41, "com.example.app", STANDARD, FULLSCREEN, true));
        assertEquals(RearCastPolicy.Presence.ON_MAIN,
                RearCastPolicy.presenceOf(Collections.emptyList(), main, 41));
        assertEquals(RearCastPolicy.Presence.GONE,
                RearCastPolicy.presenceOf(Collections.emptyList(), Collections.emptyList(), 41));
    }

    @Test
    public void launchObservationFindsRearTaskAndOwnership() {
        List<TaskEntry> rear = Arrays.asList(
                task(60, "com.example.app", STANDARD, FULLSCREEN, true),
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, false));
        RearCastPolicy.LaunchObservation created = RearCastPolicy.observeLaunch(
                rear, null, "com.example.app", Collections.emptySet(), 3);
        assertEquals(RearCastPolicy.Presence.VISIBLE, created.presence);
        assertEquals(60, created.rootTaskId);
        assertFalse(created.preexisting);

        RearCastPolicy.LaunchObservation reused = RearCastPolicy.observeLaunch(
                rear, null, "com.example.app", Collections.singleton(60), 3);
        assertTrue("启动前已有的任务不归会话", reused.preexisting);
    }

    @Test
    public void launchObservationDetectsRedirectToMain() {
        List<TaskEntry> rear = Collections.singletonList(
                task(14757, Constants.TARGET_PACKAGE, HOME, FULLSCREEN, true));
        List<TaskEntry> mainNew = Collections.singletonList(task(61, "com.example.app", STANDARD, FULLSCREEN, true));
        assertEquals(RearCastPolicy.Presence.ON_MAIN,
                RearCastPolicy.observeLaunch(rear, mainNew, "com.example.app", Collections.emptySet(), 3).presence);
        // 已有任务原本不在主屏最上层，启动后被带到最上层：同样算改派到主屏。
        assertEquals(RearCastPolicy.Presence.ON_MAIN,
                RearCastPolicy.observeLaunch(rear, mainNew, "com.example.app", Collections.singleton(61), 3).presence);
        // 启动前它就在主屏最上层：还不能判定，继续等待。
        assertEquals(RearCastPolicy.Presence.GONE,
                RearCastPolicy.observeLaunch(rear, mainNew, "com.example.app", Collections.singleton(61), 61).presence);
        assertEquals(RearCastPolicy.Presence.GONE,
                RearCastPolicy.observeLaunch(rear, null, "com.example.app", Collections.emptySet(), 3).presence);
        assertEquals(61, RearCastPolicy.topVisibleTaskId(mainNew));
        assertEquals(RearCastPolicy.UNKNOWN, RearCastPolicy.topVisibleTaskId(null));
    }

    @Test
    public void moduleTaskIsNeverCast() {
        List<TaskEntry> rear = Arrays.asList(
                task(77, Constants.MODULE_PACKAGE, STANDARD, FULLSCREEN, true),
                task(41, "com.example.app", STANDARD, FULLSCREEN, false));
        assertEquals(41, RearCastPolicy.pickRearToMain(rear).rootTaskId);
        assertNull(RearCastPolicy.pickRearToMain(Arrays.asList(
                task(77, Constants.MODULE_PACKAGE, STANDARD, FULLSCREEN, true))));
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
