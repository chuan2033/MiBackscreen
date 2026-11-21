package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import hook.HyperBackscreen.common.RearCastPolicy.LaunchObservation;
import hook.HyperBackscreen.common.RearCastPolicy.Op;
import hook.HyperBackscreen.common.RearCastPolicy.Presence;

/**
 * 背屏桌面会话的状态机：输入全部是普通数据（请求、任务位置、时间），输出是要执行的决定。
 * system_server 在投放 HandlerThread 上串行调用，本类不做同步。
 *
 * <p>状态：空闲 → （等待认证）→ 正在启动 → 显示中 → 正在结束 → 空闲。每次受理分配新的会话编号，
 * 计时事件与控制请求都带会话编号，旧会话的事件直接忽略。
 */
public final class RearDesktopSession {
    /** 启动后等待任务在背屏可见的上限。 */
    public static final long LAUNCH_TIMEOUT_MS = 5_000L;
    /** 等待原生认证的上限。 */
    public static final long AUTH_TIMEOUT_MS = 30_000L;
    /** 背屏亮着时会话任务连续不可见的上限，超过后回收会话。 */
    public static final long HIDDEN_GRACE_MS = 3_000L;
    /** 回首页、移到主屏、退出之后确认任务离开的上限。 */
    public static final long ENDING_TIMEOUT_MS = 2_000L;

    public enum State {
        IDLE("idle"), AWAITING_AUTH("awaiting_auth"), LAUNCHING("launching"), SHOWING("showing"), ENDING("ending");

        @NonNull
        public final String wire;

        State(@NonNull String wire) {
            this.wire = wire;
        }

        @Nullable
        public static State fromWire(@Nullable String value) {
            for (State state : values()) {
                if (state.wire.equals(value)) return state;
            }
            return null;
        }
    }

    public enum EndReason {
        HOME("home"), EXIT("exit"), TO_MAIN("to_main"), MOVED_TO_MAIN("moved_to_main"),
        TASK_REMOVED("task_removed"), HIDDEN("hidden"),
        LAUNCH_DENIED("launch_denied"), LAUNCH_REDIRECTED("launch_redirected"),
        LAUNCH_TIMEOUT("launch_timeout"), LAUNCH_FAILED("launch_failed"),
        AUTH_FAILED("auth_failed"), AUTH_CANCELLED("auth_cancelled"), AUTH_TIMEOUT("auth_timeout"),
        USER_SWITCHED("user_switched"), DISPLAY_REMOVED("display_removed"), UNAVAILABLE("unavailable");

        @NonNull
        public final String wire;

        EndReason(@NonNull String wire) {
            this.wire = wire;
        }

        /** 启动阶段的失败（会话从未进入显示中）。 */
        public boolean isLaunchFailure() {
            return this == LAUNCH_DENIED || this == LAUNCH_REDIRECTED || this == LAUNCH_TIMEOUT
                    || this == LAUNCH_FAILED || this == AUTH_FAILED || this == AUTH_CANCELLED
                    || this == AUTH_TIMEOUT;
        }

        /** 这次结束对应的结果码（重复请求时回报）。 */
        public int resultCode() {
            switch (this) {
                case LAUNCH_DENIED:
                case LAUNCH_REDIRECTED:
                    return Constants.REAR_CAST_RESULT_ROM_DENIED;
                case LAUNCH_TIMEOUT:
                case LAUNCH_FAILED:
                    return Constants.REAR_CAST_RESULT_MOVE_FAILED;
                case AUTH_FAILED:
                case AUTH_CANCELLED:
                case AUTH_TIMEOUT:
                    return Constants.REAR_CAST_RESULT_LOCKED;
                case UNAVAILABLE:
                    return Constants.REAR_CAST_RESULT_API_UNAVAILABLE;
                default:
                    return Constants.REAR_CAST_RESULT_DONE;
            }
        }

        @Nullable
        public static EndReason forControl(@NonNull Op op) {
            switch (op) {
                case PANEL_HOME:
                    return HOME;
                case PANEL_TO_MAIN:
                    return TO_MAIN;
                case PANEL_EXIT:
                    return EXIT;
                default:
                    return null;
            }
        }
    }

    /**
     * 会话结束后仍留在背屏上的任务怎么处理。背屏没有最近任务，留在背屏后台的任务用户看不到，
     * 之后从主屏打开同一应用时系统会复用这个任务，应用又显示在背屏上。
     */
    public enum Leftover {
        NONE,
        /** 会话创建的任务：结束任务，与在最近任务里划掉相同。 */
        REMOVE,
        /** 启动前已存在的任务：移回主屏，保留应用状态。 */
        MOVE_TO_MAIN
    }

    /**
     * 返回手势、回首页、被其它界面盖住超时、退出时任务不归会话，这几种结束方式都会让任务留在背屏后台；
     * 移到主屏、任务已移除、启动阶段的失败、用户切换、显示移除不处理。
     */
    @NonNull
    public static Leftover leftoverFor(@NonNull EndReason reason, boolean wasShown, int taskId, boolean taskOwned) {
        if (!wasShown || taskId == RearCastPolicy.UNKNOWN) return Leftover.NONE;
        switch (reason) {
            case HOME:
            case HIDDEN:
            case EXIT:
                return taskOwned ? Leftover.REMOVE : Leftover.MOVE_TO_MAIN;
            default:
                return Leftover.NONE;
        }
    }

    /** 一次输入的处理结果。 */
    public static final class Decision {
        public enum Kind {
            /** 什么也不做。 */
            NONE,
            /** 以 {@link #sessionId} 发起启动。 */
            LAUNCH,
            /** 等待当前操作的原生认证。 */
            AWAIT_AUTH,
            /** 拒绝，回报 {@link #resultCode}。 */
            REJECT,
            /** 重复请求：回报已知状态，不重复执行。 */
            KNOWN,
            /** 会话进入显示中：显示控制条。 */
            SHOW,
            /** 执行控制动作。 */
            CONTROL,
            /** 会话结束：释放控制条、恢复显示参数、发布结束通知。 */
            END
        }

        @NonNull
        public final Kind kind;
        public final int resultCode;
        public final int sessionId;
        @Nullable
        public final EndReason reason;
        /** END 时会话是否到过显示中（需要释放控制条）。 */
        public final boolean wasShown;
        /** 同一次输入中被取消的待认证会话（新请求替换旧请求时），没有为 0。 */
        public final int cancelledSessionId;
        /** END 时对仍留在背屏上的会话任务要做的处理。 */
        @NonNull
        public final Leftover leftover;
        /** {@link #leftover} 针对的根任务编号，没有为 {@link RearCastPolicy#UNKNOWN}。 */
        public final int leftoverTaskId;

        private Decision(@NonNull Kind kind, int resultCode, int sessionId, @Nullable EndReason reason,
                         boolean wasShown, int cancelledSessionId) {
            this(kind, resultCode, sessionId, reason, wasShown, cancelledSessionId, Leftover.NONE, RearCastPolicy.UNKNOWN);
        }

        private Decision(@NonNull Kind kind, int resultCode, int sessionId, @Nullable EndReason reason,
                         boolean wasShown, int cancelledSessionId, @NonNull Leftover leftover, int leftoverTaskId) {
            this.kind = kind;
            this.resultCode = resultCode;
            this.sessionId = sessionId;
            this.reason = reason;
            this.wasShown = wasShown;
            this.cancelledSessionId = cancelledSessionId;
            this.leftover = leftover;
            this.leftoverTaskId = leftoverTaskId;
        }

        static Decision none() {
            return new Decision(Kind.NONE, Constants.REAR_CAST_RESULT_DONE, 0, null, false, 0);
        }

        static Decision reject(int code, int sessionId) {
            return new Decision(Kind.REJECT, code, sessionId, null, false, 0);
        }

        @NonNull
        @Override
        public String toString() {
            return kind + " code=" + resultCode + " session=" + sessionId
                    + (reason == null ? "" : " reason=" + reason.wire);
        }
    }

    private State state = State.IDLE;
    private int nextSessionId = 1;
    private int sessionId;
    @Nullable
    private String requestId;
    private int userId = RearCastPolicy.UNKNOWN;
    @Nullable
    private String component;
    @Nullable
    private String packageName;
    private int taskId = RearCastPolicy.UNKNOWN;
    private boolean taskOwned;
    private long deadline;
    /** 显示中时任务连续不可见的起点；-1 表示当前可见或背屏熄灭。 */
    private long hiddenSince = -1L;
    private boolean confirmed;
    @Nullable
    private EndReason pendingEnd;
    private boolean shown;

    @Nullable
    private String lastRequestId;
    private int lastSessionId;
    private int lastResultCode = Constants.REAR_CAST_RESULT_DONE;
    @Nullable
    private EndReason lastEndReason;

    @NonNull
    public State state() {
        return state;
    }

    public int sessionId() {
        return state == State.IDLE ? 0 : sessionId;
    }

    public int userId() {
        return userId;
    }

    @Nullable
    public String component() {
        return component;
    }

    @Nullable
    public String packageName() {
        return packageName;
    }

    public int taskId() {
        return taskId;
    }

    /** 任务由本次会话创建；退出时只结束会话持有的任务。 */
    public boolean taskOwned() {
        return taskOwned;
    }

    public long deadline() {
        return deadline;
    }

    /** 显示中且最近一次快照确认任务在背屏可见。 */
    public boolean controlsAllowed() {
        return state == State.SHOWING && confirmed;
    }

    @Nullable
    public EndReason lastEndReason() {
        return lastEndReason;
    }

    public int lastSessionId() {
        return lastSessionId;
    }

    public int lastResultCode() {
        return lastResultCode;
    }

    /**
     * 面板请求在背屏启动应用。
     *
     * @param keyguardLocked             主屏锁定（ROM 在锁屏时另查名单）
     * @param romAuthorizationSupported ROM 为本次启动提供可验证的认证授权路径
     */
    @NonNull
    public Decision onLaunchRequest(@Nullable String requestId, int userId, @NonNull String component,
                                    @NonNull String packageName, boolean keyguardLocked,
                                    boolean romAuthorizationSupported, long now) {
        if (requestId == null || requestId.isEmpty()) {
            return Decision.reject(Constants.REAR_CAST_RESULT_BAD_REQUEST, 0);
        }
        if (state != State.IDLE && requestId.equals(this.requestId)) {
            return new Decision(Decision.Kind.KNOWN, Constants.REAR_CAST_RESULT_ACCEPTED, sessionId, null, false, 0);
        }
        if (requestId.equals(lastRequestId)) {
            return new Decision(Decision.Kind.KNOWN, lastResultCode, lastSessionId, lastEndReason, false, 0);
        }
        int cancelled = 0;
        if (state == State.AWAITING_AUTH) {
            // 用户换选了应用：取消旧的待认证请求。
            cancelled = sessionId;
            finish(EndReason.AUTH_CANCELLED);
        } else if (state != State.IDLE) {
            return Decision.reject(Constants.REAR_CAST_RESULT_BUSY, sessionId);
        }
        if (keyguardLocked && !romAuthorizationSupported) {
            lastRequestId = requestId;
            lastSessionId = 0;
            lastResultCode = Constants.REAR_CAST_RESULT_LOCKED;
            lastEndReason = null;
            return new Decision(Decision.Kind.REJECT, Constants.REAR_CAST_RESULT_LOCKED, 0, null, false, cancelled);
        }
        sessionId = nextSessionId++;
        this.requestId = requestId;
        this.userId = userId;
        this.component = component;
        this.packageName = packageName;
        taskId = RearCastPolicy.UNKNOWN;
        taskOwned = false;
        hiddenSince = -1L;
        confirmed = false;
        pendingEnd = null;
        shown = false;
        if (keyguardLocked) {
            state = State.AWAITING_AUTH;
            deadline = now + AUTH_TIMEOUT_MS;
            return new Decision(Decision.Kind.AWAIT_AUTH, Constants.REAR_CAST_RESULT_ACCEPTED, sessionId, null,
                    false, cancelled);
        }
        state = State.LAUNCHING;
        deadline = now + LAUNCH_TIMEOUT_MS;
        return new Decision(Decision.Kind.LAUNCH, Constants.REAR_CAST_RESULT_ACCEPTED, sessionId, null,
                false, cancelled);
    }

    /**
     * 认证流程完成。只有原生认证成功且系统侧核验到 ROM 对本次启动的授权时才继续启动；
     * 只有界面上的成功通知（没有 ROM 授权）按认证失败结束。
     */
    @NonNull
    public Decision onAuthResult(int sessionId, boolean nativeSuccess, boolean romAuthorized, long now) {
        if (state != State.AWAITING_AUTH || sessionId != this.sessionId) return Decision.none();
        if (now >= deadline) return end(EndReason.AUTH_TIMEOUT);
        if (!nativeSuccess || !romAuthorized) return end(EndReason.AUTH_FAILED);
        state = State.LAUNCHING;
        deadline = now + LAUNCH_TIMEOUT_MS;
        return new Decision(Decision.Kind.LAUNCH, Constants.REAR_CAST_RESULT_ACCEPTED, sessionId, null, false, 0);
    }

    /** 关闭面板、切换用户、熄灭背屏时取消待认证请求。 */
    @NonNull
    public Decision onAuthCancelled() {
        if (state != State.AWAITING_AUTH) return Decision.none();
        return end(EndReason.AUTH_CANCELLED);
    }

    /** 启动调用失败或 ROM 中止了本次启动。 */
    @NonNull
    public Decision onLaunchFailed(int sessionId, @NonNull EndReason reason) {
        if (state != State.LAUNCHING || sessionId != this.sessionId) return Decision.none();
        return end(reason);
    }

    /** 启动后的一次任务快照。 */
    @NonNull
    public Decision onLaunchObserved(int sessionId, @NonNull LaunchObservation observation, long now) {
        if (state != State.LAUNCHING || sessionId != this.sessionId) return Decision.none();
        if (observation.presence == Presence.VISIBLE) {
            state = State.SHOWING;
            taskId = observation.rootTaskId;
            taskOwned = !observation.preexisting;
            confirmed = true;
            shown = true;
            hiddenSince = -1L;
            return new Decision(Decision.Kind.SHOW, Constants.REAR_CAST_RESULT_DONE, sessionId, null, true, 0);
        }
        if (observation.presence == Presence.ON_MAIN) return end(EndReason.LAUNCH_REDIRECTED);
        if (now >= deadline) return end(EndReason.LAUNCH_TIMEOUT);
        return Decision.none();
    }

    /**
     * 显示中 / 正在结束时的一次任务快照。
     *
     * @param rearDisplayOn 背屏亮着；熄灭期间任务不可见不计入回收时限
     */
    @NonNull
    public Decision onSnapshot(@NonNull Presence presence, boolean rearDisplayOn, long now) {
        if (state == State.SHOWING) {
            switch (presence) {
                case VISIBLE:
                    confirmed = true;
                    hiddenSince = -1L;
                    return Decision.none();
                case HOME_ON_TOP:
                    return end(EndReason.HOME);
                case ON_MAIN:
                    return end(EndReason.MOVED_TO_MAIN);
                case GONE:
                    return end(EndReason.TASK_REMOVED);
                case HIDDEN:
                default:
                    confirmed = false;
                    if (!rearDisplayOn) {
                        hiddenSince = -1L;
                        return Decision.none();
                    }
                    if (hiddenSince < 0L) hiddenSince = now;
                    return now - hiddenSince >= HIDDEN_GRACE_MS ? end(EndReason.HIDDEN) : Decision.none();
            }
        }
        if (state == State.ENDING && pendingEnd != null) {
            boolean left;
            switch (pendingEnd) {
                case HOME:
                    left = presence != Presence.VISIBLE;
                    break;
                case TO_MAIN:
                    left = presence == Presence.ON_MAIN || presence == Presence.GONE;
                    break;
                case EXIT:
                default:
                    left = presence == Presence.GONE || presence == Presence.HOME_ON_TOP
                            || presence == Presence.ON_MAIN;
                    break;
            }
            if (left || now >= deadline) return end(pendingEnd);
        }
        return Decision.none();
    }

    /**
     * 控制条的请求。返回只对当前会话、显示中且已确认可见的任务执行；回首页、移到主屏、退出进入正在结束，
     * 同一会话的同类请求重复到达时回报已执行。
     */
    @NonNull
    public Decision onControl(@NonNull Op op, int sessionId, long now) {
        if (!op.isSessionControl()) return Decision.reject(Constants.REAR_CAST_RESULT_BAD_REQUEST, sessionId);
        EndReason reason = EndReason.forControl(op);
        if (reason != null) {
            if (state == State.ENDING && sessionId == this.sessionId && pendingEnd == reason) {
                return new Decision(Decision.Kind.KNOWN, Constants.REAR_CAST_RESULT_DONE, sessionId, reason, true, 0);
            }
            if (state == State.IDLE && sessionId == lastSessionId && lastEndReason == reason) {
                return new Decision(Decision.Kind.KNOWN, Constants.REAR_CAST_RESULT_DONE, sessionId, reason, false, 0);
            }
        }
        if (state != State.SHOWING || sessionId != this.sessionId) {
            return Decision.reject(Constants.REAR_CAST_RESULT_NO_SESSION, sessionId);
        }
        if (!confirmed) return Decision.reject(Constants.REAR_CAST_RESULT_BUSY, sessionId);
        if (reason != null) {
            state = State.ENDING;
            pendingEnd = reason;
            deadline = now + ENDING_TIMEOUT_MS;
        }
        return new Decision(Decision.Kind.CONTROL, Constants.REAR_CAST_RESULT_DONE, sessionId, reason, true, 0);
    }

    /** 控制动作没有执行成功（例如移到主屏被系统拒绝）：回到显示中。 */
    @NonNull
    public Decision onControlFailed(int sessionId) {
        if (state != State.ENDING || sessionId != this.sessionId) return Decision.none();
        state = State.SHOWING;
        pendingEnd = null;
        return Decision.none();
    }

    /** 带会话编号的计时到期；旧会话的计时直接忽略。 */
    @NonNull
    public Decision onTimeout(int sessionId, long now) {
        if (sessionId != this.sessionId || now < deadline) return Decision.none();
        switch (state) {
            case AWAITING_AUTH:
                return end(EndReason.AUTH_TIMEOUT);
            case LAUNCHING:
                return end(EndReason.LAUNCH_TIMEOUT);
            case ENDING:
                return pendingEnd == null ? Decision.none() : end(pendingEnd);
            default:
                return Decision.none();
        }
    }

    /** 用户切换、背屏移除、系统接口不可用时立即结束。 */
    @NonNull
    public Decision onSystemEnd(@NonNull EndReason reason) {
        if (state == State.IDLE) return Decision.none();
        return end(reason);
    }

    private Decision end(@NonNull EndReason reason) {
        int ended = sessionId;
        boolean wasShown = shown;
        int endedTask = taskId;
        Leftover leftover = leftoverFor(reason, wasShown, endedTask, taskOwned);
        finish(reason);
        return new Decision(Decision.Kind.END, reason.resultCode(), ended, reason, wasShown, 0,
                leftover, leftover == Leftover.NONE ? RearCastPolicy.UNKNOWN : endedTask);
    }

    private void finish(@NonNull EndReason reason) {
        lastRequestId = requestId;
        lastSessionId = sessionId;
        lastResultCode = reason.resultCode();
        lastEndReason = reason;
        state = State.IDLE;
        requestId = null;
        component = null;
        packageName = null;
        taskId = RearCastPolicy.UNKNOWN;
        taskOwned = false;
        hiddenSince = -1L;
        confirmed = false;
        pendingEnd = null;
        shown = false;
    }
}
