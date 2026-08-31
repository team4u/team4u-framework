package com.team4u.framework.flow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * 运行时帧栈结果归约与状态转移协调器（Runtime Stack Frame Reducer）。
 *
 * <p>负责在子物理节点执行完毕返回 {@link Outcome} 时，驱动父物理帧进行状态转移裁决：
 * <ul>
 *   <li><b>Sequence 归约</b>：若子节点 Accepted 则更新当前数据并压入下一个子步骤；若已是最后一步则作为整段结果完成；若非 Accepted 则短路向上传播；</li>
 *   <li><b>Route 归约</b>：处理选择器判别结果并分发压入匹配的 case 或 otherwise 分支；</li>
 *   <li><b>Fallback 归约</b>：根据 Skipped/Failed 触发条件依次尝试备选分支，所有分支尝试完毕则透传最终结果；</li>
 *   <li><b>Control 归约</b>：处理 Timeout 超时判定、Retry 重试轮次推进与退避、无状态策略 {@link Policy#after} 观察以及持久化策略 {@link PersistentPolicy#after} 状态提交与定时重试（RetryAt）。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
final class FrameReducer {
    private FrameReducer() { }

    /**
     * 将子节点的四态结果交给父帧进行归约。
     *
     * @param machine 状态机实例
     * @param frame   当前父帧
     * @param child   子节点返回的四态结果
     * @return 若父帧自身已执行完成则返回父帧的最终 Outcome（向上传递）；若压入了新的子节点继续推进则返回 null
     */
    static Outcome<?> consume(SerialMachine machine, RuntimeFrame frame, Outcome<?> child) {
        if (frame.node instanceof PlanNode.Sequence) {
            return sequence(machine, frame, (PlanNode.Sequence) frame.node, child);
        } else if (frame.node instanceof PlanNode.Route) {
            return route(machine, frame, (PlanNode.Route) frame.node, child);
        } else if (frame.node instanceof PlanNode.Fallback) {
            return fallback(machine, frame, (PlanNode.Fallback) frame.node, child);
        } else if (frame.node instanceof PlanNode.Control) {
            return control(machine, frame, (PlanNode.Control) frame.node, child);
        } else {
            throw new IllegalStateException("Leaf node cannot consume child outcome: "
                    + frame.node.getClass());
        }
    }


    /**
     * Sequence 归约：子帧 Accepted 后更新 current，推进到下一个子节点；
     * 全部子节点完成则把 current 作为本帧输出。
     */
    private static Outcome<?> sequence(SerialMachine machine, RuntimeFrame frame,
                                       PlanNode.Sequence sequence, Outcome<?> child) {
        if (!(child instanceof Outcome.Accepted)) return child;
        Outcome.Accepted<?> accepted = (Outcome.Accepted<?>) child;
        frame.current = accepted.value();
        frame.index++;
        if (frame.index >= sequence.children().size()) {
            return Outcome.accepted(frame.current);
        }
        machine.push(sequence.children().get(frame.index), frame.current);
        return null;
    }

    /**
     * Route 归约：phase 1 处理 selector 结果并选中分支（压入 case/otherwise），
     * phase 2 直接透传选中分支的 Outcome。
     */
    private static Outcome<?> route(SerialMachine machine, RuntimeFrame frame,
                                    PlanNode.Route route, Outcome<?> child) {
        if (frame.phase == 1) {
            if (!(child instanceof Outcome.Accepted)) return child;
            Outcome.Accepted<?> accepted = (Outcome.Accepted<?>) child;
            int selected = -1;
            for (int index = 0; index < route.cases().size(); index++) {
                if (route.cases().get(index).key().equals(accepted.value())) {
                    selected = index;
                    break;
                }
            }
            frame.phase = 2;
            frame.index = selected;
            if (selected >= 0) {
                frame.selected = "case:" + selected;
                machine.push(route.cases().get(selected).branch(), frame.entry);
            } else if (route.otherwise() != null) {
                frame.selected = "otherwise";
                machine.push(route.otherwise(), frame.entry);
            } else {
                return Outcome.skipped(Reason.of("NO_ROUTE",
                        "No route case matched the selector"));
            }
            machine.event(FlowObserver.Type.ROUTE_SELECTED, route.descriptor(),
                    Collections.singletonMap("branch", frame.selected));
            return null;
        }
        if (frame.phase == 2) return child;
        throw new IllegalStateException("Invalid Route phase at " + route.descriptor().path());
    }

    /**
     * Fallback 归约：若当前分支未触发且仍有后续分支，则把入口（FAILED 触发时包为 Recovery）
     * 作为下一分支输入并压入下一分支；否则透传当前 Outcome。
     */
    private static Outcome<?> fallback(SerialMachine machine, RuntimeFrame frame,
                                       PlanNode.Fallback fallback, Outcome<?> child) {
        boolean triggered = fallback.trigger() == PlanNode.Fallback.Trigger.SKIPPED
                ? child instanceof Outcome.Skipped
                : child instanceof Outcome.Failed;
        if (!triggered || frame.index + 1 >= fallback.branches().size()) return child;
        frame.index++;
        Object input = frame.entry;
        if (fallback.trigger() == PlanNode.Fallback.Trigger.FAILED) {
            input = new Recovery<Object>(frame.entry, ((Outcome.Failed<?>) child).failure());
        }
        frame.selected = "branch:" + frame.index;
        machine.push(fallback.branches().get(frame.index), input);
        machine.event(FlowObserver.Type.FALLBACK_SELECTED, fallback.descriptor(),
                Collections.singletonMap("branch", frame.selected));
        return null;
    }

    private static Outcome<?> control(SerialMachine machine, RuntimeFrame frame,
                                      PlanNode.Control control, Outcome<?> child) {
        switch (control.kind()) {
            case TIMEOUT:
                return timeout(frame, child);
            case RETRY:
                return retry(machine, frame, control, child);
            case POLICY:
                return policy(machine, frame, control, child);
            case PERSISTENT_POLICY:
                return persistent(machine, frame, control, child);
            default:
                throw new IllegalStateException("Unknown control kind: " + control.kind());
        }
    }

    /** Timeout 归约：清除本作用域 deadline，若已超时则转换为 TIMEOUT 失败。 */
    private static Outcome<?> timeout(RuntimeFrame frame, Outcome<?> child) {
        boolean timedOut = frame.deadline != null && !Instant.now().isBefore(frame.deadline);
        frame.deadline = null;
        return timedOut ? SerialMachine.timeoutFailure() : child;
    }

    /**
     * Retry 归约：body 失败且未达上限时递增 attempt、计算 backoff 唤醒时间并进入等待阶段；
     * 否则透传 Outcome（含重试耗尽后的失败）。
     */
    private static Outcome<?> retry(SerialMachine machine, RuntimeFrame frame,
                                    PlanNode.Control control, Outcome<?> child) {
        Retry retry = (Retry) control.configuration();
        if (child instanceof Outcome.Failed && frame.attempt < retry.maxAttempts()) {
            frame.attempt++;
            frame.wake = Instant.now().plus(retry.backoff());
            frame.phase = 2;
            machine.waitingEvent(control, frame);
            return null;
        }
        return child;
    }

    /**
     * 一次性 Policy 的 after 回调：在 CallbackRunner 中执行，受 deadline 与取消约束。
     * Policy 不能改变 Outcome，仅能作为观测/记录点；超时与失败会转换为对应 Outcome 或取消。
     */
    @SuppressWarnings("unchecked")
    private static Outcome<?> policy(SerialMachine machine, RuntimeFrame frame,
                                     PlanNode.Control control, Outcome<?> child) {
        Policy<Object> policy = (Policy<Object>) control.policy().instance();
        CallbackRunner.Result<Boolean> call = machine.callbacks().call(signal -> {
            policy.after(machine.context(frame, control, signal), frame.key,
                    Completion.from(child));
            return Boolean.TRUE;
        }, machine.deadline());
        if (machine.cancelled()) throw new CancellationException(
                "flow execution was cancelled");
        if (call.timeout()) return SerialMachine.timeoutFailure();
        if (call.failure() != null) return callbackFailure(machine, call.failure());
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("outcome", child.kind().name());
        machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(), attrs);
        return child;
    }

    /**
     * PersistentPolicy 的 after 回调：根据返回的决策更新 policyState。
     * Return 透传 Outcome 并保留状态；RetryAt 进入等待阶段并递增 attempt，等待恢复后重试 body。
     */
    @SuppressWarnings("unchecked")
    private static Outcome<?> persistent(SerialMachine machine, RuntimeFrame frame,
                                         PlanNode.Control control, Outcome<?> child) {
        PersistentPolicy<Object, Object> policy =
                (PersistentPolicy<Object, Object>) control.policy().instance();
        CallbackRunner.Result<PersistentPolicy.After<Object>> call =
                machine.callbacks().call(signal -> Objects.requireNonNull(policy.after(
                        machine.context(frame, control, signal), frame.key, frame.policyState,
                        Completion.from(child)), "policy after decision must not be null"),
                        machine.deadline());
        if (machine.cancelled()) throw new CancellationException(
                "flow execution was cancelled");
        if (call.timeout()) return SerialMachine.timeoutFailure();
        if (call.failure() != null) return callbackFailure(machine, call.failure());
        PersistentPolicy.After<Object> decision = call.value();
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put("attempt", Integer.toString(frame.attempt));
        attrs.put("outcome", child.kind().name());
        machine.event(FlowObserver.Type.POLICY_AFTER, control.descriptor(), attrs);
        if (decision instanceof PersistentPolicy.Return) {
            PersistentPolicy.Return<Object> returning = (PersistentPolicy.Return<Object>) decision;
            frame.policyState = returning.state();
            return child;
        } else if (decision instanceof PersistentPolicy.RetryAt) {
            PersistentPolicy.RetryAt<Object> retry = (PersistentPolicy.RetryAt<Object>) decision;
            frame.policyState = retry.state();
            frame.wake = retry.instant();
            frame.attempt++;
            frame.phase = 3;
            machine.waitingEvent(control, frame);
            return null;
        } else {
            throw new IllegalStateException("Unknown PersistentPolicy.After decision: "
                    + (decision == null ? "null" : decision.getClass().getName()));
        }
    }

    private static Outcome<?> callbackFailure(SerialMachine machine, Throwable failure) {
        if (machine.cancelled()) throw new CancellationException(
                "flow execution was cancelled");
        return machine.policyFailure(failure);
    }
}
