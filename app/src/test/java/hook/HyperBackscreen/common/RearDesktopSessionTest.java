package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import hook.HyperBackscreen.common.RearCastPolicy.LaunchObservation;
import hook.HyperBackscreen.common.RearCastPolicy.Op;
import hook.HyperBackscreen.common.RearCastPolicy.Presence;
import hook.HyperBackscreen.common.RearDesktopSession.Decision.Kind;
import hook.HyperBackscreen.common.RearDesktopSession.EndReason;
import hook.HyperBackscreen.common.RearDesktopSession.State;

public class RearDesktopSessionTest {
    private static final String COMPONENT = "com.example.app/.Main";
    private static final String PACKAGE = "com.example.app";

    private static RearDesktopSession.Decision launch(RearDesktopSession session, String requestId, long now) {
        return session.onLaunchRequest(requestId, 0, COMPONENT, PACKAGE, false, false, now);
    }

    private static LaunchObservation visible(int taskId, boolean preexisting) {
        return new LaunchObservation(Presence.VISIBLE, taskId, preexisting);
    }

    private static final LaunchObservation WAITING = new LaunchObservation(Presence.GONE, -1, false);

    /** 启动到显示中，返回会话编号。 */
    private static int showing(RearDesktopSession session, String requestId, long now) {
        RearDesktopSession.Decision accepted = launch(session, requestId, now);
        assertEquals(Kind.LAUNCH, accepted.kind);
        RearDesktopSession.Decision shown = session.onLaunchObserved(accepted.sessionId, visible(41, false), now + 300);
        assertEquals(Kind.SHOW, shown.kind);
        return accepted.sessionId;
    }

    @Test
    public void successfulLaunchReachesShowingAndBindsTask() {
        RearDesktopSession session = new RearDesktopSession();
        RearDesktopSession.Decision accepted = launch(session, "r1", 1_000);
        assertEquals(Constants.REAR_CAST_RESULT_ACCEPTED, accepted.resultCode);
        assertEquals(State.LAUNCHING, session.state());
        assertEquals(Kind.NONE, session.onLaunchObserved(accepted.sessionId, WAITING, 1_200).kind);
        assertFalse(session.controlsAllowed());

        assertEquals(Kind.SHOW, session.onLaunchObserved(accepted.sessionId, visible(41, false), 1_400).kind);
        assertEquals(State.SHOWING, session.state());
        assertEquals(41, session.taskId());
        assertTrue(session.taskOwned());
        assertTrue(session.controlsAllowed());
    }

    @Test
    public void backToRearHomeRemovesTaskTheSessionCreated() {
        RearDesktopSession session = new RearDesktopSession();
        int id = showing(session, "r1", 0);
        assertEquals(Kind.NONE, session.onSnapshot(Presence.VISIBLE, true, 500).kind);
        // 返回手势让应用退到后台，背屏首页盖在上面。
        RearDesktopSession.Decision ended = session.onSnapshot(Presence.HOME_ON_TOP, true, 600);
        assertEquals(Kind.END, ended.kind);
        assertEquals(id, ended.sessionId);
        assertEquals(EndReason.HOME, ended.reason);
        assertEquals(RearDesktopSession.Leftover.REMOVE, ended.leftover);
        assertEquals(41, ended.leftoverTaskId);
    }

    @Test
    public void preexistingTaskLeftOnRearMovesBackToMain() {
        RearDesktopSession session = new RearDesktopSession();
        int id = launch(session, "r1", 0).sessionId;
        assertEquals(Kind.SHOW, session.onLaunchObserved(id, visible(52, true), 300).kind);
        RearDesktopSession.Decision ended = session.onSnapshot(Presence.HOME_ON_TOP, true, 600);
        assertEquals(RearDesktopSession.Leftover.MOVE_TO_MAIN, ended.leftover);
        assertEquals(52, ended.leftoverTaskId);
    }

    @Test
    public void leftoverOnlyForEndingsThatKeepTheTaskOnRear() {
        assertEquals(RearDesktopSession.Leftover.REMOVE,
                RearDesktopSession.leftoverFor(EndReason.HIDDEN, true, 7, true));
        assertEquals(RearDesktopSession.Leftover.MOVE_TO_MAIN,
                RearDesktopSession.leftoverFor(EndReason.EXIT, true, 7, false));
        for (EndReason reason : new EndReason[]{EndReason.TO_MAIN, EndReason.MOVED_TO_MAIN,
                EndReason.TASK_REMOVED, EndReason.USER_SWITCHED, EndReason.DISPLAY_REMOVED,
                EndReason.LAUNCH_TIMEOUT, EndReason.UNAVAILABLE}) {
            assertEquals(reason.wire, RearDesktopSession.Leftover.NONE,
                    RearDesktopSession.leftoverFor(reason, true, 7, true));
        }
        assertEquals(RearDesktopSession.Leftover.NONE,
                RearDesktopSession.leftoverFor(EndReason.HOME, false, 7, true));
        assertEquals(RearDesktopSession.Leftover.NONE,
                RearDesktopSession.leftoverFor(EndReason.HOME, true, RearCastPolicy.UNKNOWN, true));
    }

    @Test
    public void movedToMainLeavesTaskAlone() {
        RearDesktopSession session = new RearDesktopSession();
        showing(session, "r1", 0);
        RearDesktopSession.Decision ended = session.onSnapshot(Presence.ON_MAIN, true, 600);
        assertEquals(EndReason.MOVED_TO_MAIN, ended.reason);
        assertEquals(RearDesktopSession.Leftover.NONE, ended.leftover);
        assertEquals(RearCastPolicy.UNKNOWN, ended.leftoverTaskId);
    }

    @Test
    public void launchTimesOutWhenTaskNeverBecomesVisible() {
        RearDesktopSession session = new RearDesktopSession();
        int id = launch(session, "r1", 0).sessionId;
        assertEquals(Kind.NONE, session.onTimeout(id, RearDesktopSession.LAUNCH_TIMEOUT_MS - 1).kind);
        RearDesktopSession.Decision ended = session.onTimeout(id, RearDesktopSession.LAUNCH_TIMEOUT_MS);
        assertEquals(Kind.END, ended.kind);
        assertEquals(EndReason.LAUNCH_TIMEOUT, ended.reason);
        assertFalse(ended.wasShown);
        assertEquals(State.IDLE, session.state());
    }

    @Test
    public void romDenialAndRedirectEndAsLaunchFailures() {
        RearDesktopSession session = new RearDesktopSession();
        int denied = launch(session, "r1", 0).sessionId;
        RearDesktopSession.Decision end = session.onLaunchFailed(denied, EndReason.LAUNCH_DENIED);
        assertEquals(Constants.REAR_CAST_RESULT_ROM_DENIED, end.resultCode);

        int redirected = launch(session, "r2", 10).sessionId;
        RearDesktopSession.Decision redirect = session.onLaunchObserved(redirected,
                new LaunchObservation(Presence.ON_MAIN, 61, false), 20);
        assertEquals(Kind.END, redirect.kind);
        assertEquals(EndReason.LAUNCH_REDIRECTED, redirect.reason);
        assertTrue(redirect.reason.isLaunchFailure());
    }

    @Test
    public void duplicateRequestReportsKnownStateWithoutRelaunching() {
        RearDesktopSession session = new RearDesktopSession();
        int id = launch(session, "r1", 0).sessionId;
        RearDesktopSession.Decision again = launch(session, "r1", 50);
        assertEquals(Kind.KNOWN, again.kind);
        assertEquals(id, again.sessionId);

        session.onLaunchFailed(id, EndReason.LAUNCH_DENIED);
        RearDesktopSession.Decision late = launch(session, "r1", 9_000);
        assertEquals("结束后重复投递的旧请求回报原结果", Kind.KNOWN, late.kind);
        assertEquals(Constants.REAR_CAST_RESULT_ROM_DENIED, late.resultCode);
        assertEquals(State.IDLE, session.state());
    }

    @Test
    public void newRequestWhileBusyIsRejected() {
        RearDesktopSession session = new RearDesktopSession();
        launch(session, "r1", 0);
        RearDesktopSession.Decision busy = launch(session, "r2", 10);
        assertEquals(Kind.REJECT, busy.kind);
        assertEquals(Constants.REAR_CAST_RESULT_BUSY, busy.resultCode);
        assertEquals(Constants.REAR_CAST_RESULT_BAD_REQUEST, launch(session, "", 10).resultCode);
    }

    @Test
    public void staleSessionEventsAreIgnored() {
        RearDesktopSession session = new RearDesktopSession();
        int first = launch(session, "r1", 0).sessionId;
        session.onLaunchFailed(first, EndReason.LAUNCH_FAILED);
        int second = showing(session, "r2", 100);
        assertTrue(second != first);
        assertEquals(Kind.NONE, session.onTimeout(first, 100_000).kind);
        assertEquals(Kind.NONE, session.onLaunchObserved(first, visible(99, false), 200).kind);
        assertEquals(Constants.REAR_CAST_RESULT_NO_SESSION, session.onControl(Op.PANEL_BACK, first, 300).resultCode);
        assertEquals(State.SHOWING, session.state());
        assertEquals(41, session.taskId());
    }

    @Test
    public void lockedLaunchWithoutRomAuthorizationIsRejected() {
        RearDesktopSession session = new RearDesktopSession();
        RearDesktopSession.Decision locked = session.onLaunchRequest("r1", 0, COMPONENT, PACKAGE, true, false, 0);
        assertEquals(Kind.REJECT, locked.kind);
        assertEquals(Constants.REAR_CAST_RESULT_LOCKED, locked.resultCode);
        assertEquals(State.IDLE, session.state());
        assertEquals(Constants.REAR_CAST_RESULT_LOCKED, launch(session, "r1", 5).resultCode);
    }

    @Test
    public void uiSuccessNoticeWithoutRomAuthorizationDoesNotLaunch() {
        RearDesktopSession session = new RearDesktopSession();
        RearDesktopSession.Decision waiting = session.onLaunchRequest("r1", 0, COMPONENT, PACKAGE, true, true, 0);
        assertEquals(Kind.AWAIT_AUTH, waiting.kind);
        RearDesktopSession.Decision result = session.onAuthResult(waiting.sessionId, true, false, 100);
        assertEquals(Kind.END, result.kind);
        assertEquals(EndReason.AUTH_FAILED, result.reason);
        assertEquals(Constants.REAR_CAST_RESULT_LOCKED, result.resultCode);
    }

    @Test
    public void authorizedAuthContinuesToLaunchAndLateResultIsDropped() {
        RearDesktopSession session = new RearDesktopSession();
        int id = session.onLaunchRequest("r1", 0, COMPONENT, PACKAGE, true, true, 0).sessionId;
        assertEquals(Kind.LAUNCH, session.onAuthResult(id, true, true, 1_000).kind);
        assertEquals(State.LAUNCHING, session.state());
        assertEquals("晚到的认证结果", Kind.NONE, session.onAuthResult(id, true, true, 1_100).kind);

        RearDesktopSession timedOut = new RearDesktopSession();
        int late = timedOut.onLaunchRequest("r2", 0, COMPONENT, PACKAGE, true, true, 0).sessionId;
        assertEquals(EndReason.AUTH_TIMEOUT,
                timedOut.onAuthResult(late, true, true, RearDesktopSession.AUTH_TIMEOUT_MS).reason);
    }

    @Test
    public void choosingAnotherAppCancelsPendingAuth() {
        RearDesktopSession session = new RearDesktopSession();
        int first = session.onLaunchRequest("r1", 0, COMPONENT, PACKAGE, true, true, 0).sessionId;
        RearDesktopSession.Decision second = session.onLaunchRequest("r2", 0, "com.example.other/.A",
                "com.example.other", true, true, 10);
        assertEquals(Kind.AWAIT_AUTH, second.kind);
        assertEquals(first, second.cancelledSessionId);
        assertEquals(EndReason.AUTH_CANCELLED, session.onAuthCancelled().reason);
    }

    @Test
    public void backRequiresConfirmedVisibleTask() {
        RearDesktopSession session = new RearDesktopSession();
        int id = showing(session, "r1", 0);
        RearDesktopSession.Decision back = session.onControl(Op.PANEL_BACK, id, 500);
        assertEquals(Kind.CONTROL, back.kind);
        assertEquals(State.SHOWING, session.state());

        session.onSnapshot(Presence.HIDDEN, true, 600);
        assertFalse(session.controlsAllowed());
        assertEquals("任务无法确认时暂停控制", Constants.REAR_CAST_RESULT_BUSY,
                session.onControl(Op.PANEL_BACK, id, 650).resultCode);
        session.onSnapshot(Presence.VISIBLE, true, 700);
        assertEquals(Kind.CONTROL, session.onControl(Op.PANEL_BACK, id, 750).kind);
    }

    @Test
    public void hiddenTaskIsReclaimedAfterGraceOnlyWhileDisplayOn() {
        RearDesktopSession session = new RearDesktopSession();
        showing(session, "r1", 0);
        assertEquals(Kind.NONE, session.onSnapshot(Presence.HIDDEN, false, 1_000).kind);
        assertEquals("背屏熄灭不计时", Kind.NONE, session.onSnapshot(Presence.HIDDEN, false, 60_000).kind);
        assertEquals(Kind.NONE, session.onSnapshot(Presence.HIDDEN, true, 61_000).kind);
        RearDesktopSession.Decision end = session.onSnapshot(Presence.HIDDEN, true,
                61_000 + RearDesktopSession.HIDDEN_GRACE_MS);
        assertEquals(EndReason.HIDDEN, end.reason);
        assertTrue(end.wasShown);
    }

    @Test
    public void taskEventsEndShowingSession() {
        assertEquals(EndReason.HOME, endBySnapshot(Presence.HOME_ON_TOP));
        assertEquals(EndReason.MOVED_TO_MAIN, endBySnapshot(Presence.ON_MAIN));
        assertEquals(EndReason.TASK_REMOVED, endBySnapshot(Presence.GONE));
    }

    private static EndReason endBySnapshot(Presence presence) {
        RearDesktopSession session = new RearDesktopSession();
        showing(session, "r1", 0);
        return session.onSnapshot(presence, true, 1_000).reason;
    }

    @Test
    public void homeEndsAfterTaskLeavesForeground() {
        RearDesktopSession session = new RearDesktopSession();
        int id = showing(session, "r1", 0);
        RearDesktopSession.Decision home = session.onControl(Op.PANEL_HOME, id, 1_000);
        assertEquals(Kind.CONTROL, home.kind);
        assertEquals(State.ENDING, session.state());
        assertEquals(Kind.KNOWN, session.onControl(Op.PANEL_HOME, id, 1_050).kind);
        assertEquals(Kind.NONE, session.onSnapshot(Presence.VISIBLE, true, 1_100).kind);
        RearDesktopSession.Decision end = session.onSnapshot(Presence.HOME_ON_TOP, true, 1_200);
        assertEquals(EndReason.HOME, end.reason);
        assertEquals("结束后再次回首页按已执行处理", Kind.KNOWN, session.onControl(Op.PANEL_HOME, id, 1_300).kind);
    }

    @Test
    public void toMainEndsOnlyWhenTaskReachesMain() {
        RearDesktopSession session = new RearDesktopSession();
        int id = showing(session, "r1", 0);
        session.onControl(Op.PANEL_TO_MAIN, id, 1_000);
        assertEquals(Kind.NONE, session.onSnapshot(Presence.HOME_ON_TOP, true, 1_100).kind);
        assertEquals(EndReason.TO_MAIN, session.onSnapshot(Presence.ON_MAIN, true, 1_200).reason);
    }

    @Test
    public void failedToMainReturnsToShowing() {
        RearDesktopSession session = new RearDesktopSession();
        int id = showing(session, "r1", 0);
        session.onControl(Op.PANEL_TO_MAIN, id, 1_000);
        session.onControlFailed(id);
        assertEquals(State.SHOWING, session.state());
        assertTrue(session.controlsAllowed());
    }

    @Test
    public void exitTimesOutIntoEndAndCanBeRepeated() {
        RearDesktopSession session = new RearDesktopSession();
        int id = showing(session, "r1", 0);
        session.onControl(Op.PANEL_EXIT, id, 1_000);
        assertEquals(Kind.NONE, session.onTimeout(id, 1_500).kind);
        RearDesktopSession.Decision end = session.onTimeout(id, 1_000 + RearDesktopSession.ENDING_TIMEOUT_MS);
        assertEquals(EndReason.EXIT, end.reason);
        assertEquals(Kind.KNOWN, session.onControl(Op.PANEL_EXIT, id, 5_000).kind);
        assertEquals(Constants.REAR_CAST_RESULT_NO_SESSION, session.onControl(Op.PANEL_HOME, id, 5_000).resultCode);
    }

    @Test
    public void newSessionAfterExit() {
        RearDesktopSession session = new RearDesktopSession();
        int id = showing(session, "r1", 0);
        session.onControl(Op.PANEL_EXIT, id, 1_000);
        session.onSnapshot(Presence.GONE, true, 1_100);
        int next = showing(session, "r2", 2_000);
        assertTrue(next > id);
        assertEquals(State.SHOWING, session.state());
    }

    @Test
    public void userSwitchAndDisplayRemovalEndSession() {
        RearDesktopSession session = new RearDesktopSession();
        showing(session, "r1", 0);
        assertEquals(EndReason.USER_SWITCHED, session.onSystemEnd(EndReason.USER_SWITCHED).reason);
        assertEquals(Kind.NONE, session.onSystemEnd(EndReason.DISPLAY_REMOVED).kind);
        launch(session, "r2", 10);
        assertEquals(EndReason.DISPLAY_REMOVED, session.onSystemEnd(EndReason.DISPLAY_REMOVED).reason);
    }

    @Test
    public void preexistingTaskIsNotOwned() {
        RearDesktopSession session = new RearDesktopSession();
        int id = launch(session, "r1", 0).sessionId;
        session.onLaunchObserved(id, visible(17, true), 100);
        assertFalse(session.taskOwned());
    }

    @Test
    public void stateWireNamesRoundTrip() {
        for (State state : State.values()) {
            assertEquals(state, State.fromWire(state.wire));
        }
        assertEquals(null, State.fromWire("unknown"));
    }
}
