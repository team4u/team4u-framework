package com.team4u.framework.flow.durable.engine;


import java.util.List;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.durable.DurableException;
import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.SnapshotCodec;

/**
 * 崩溃恢复快照防御性拓扑与状态校验器（Restored State Defensive Validator）。
 *
 * <p>核心校验规则：
 * <ul>
 *   <li><b>根帧拓扑一致性</b>：恢复快照的根帧节点必须与当前代码编译出的 Definition 根节点完全一致；</li>
 *   <li><b>父子帧拓扑与游标匹配</b>：父帧声明的当前子步骤下标（index）对应的物理子节点必须与栈中的实际子帧完全匹配；</li>
 *   <li><b>阶段（Phase）与状态自洽</b>：校验 Sequence/Route/Fallback/Control/Parallel 各节点的 phase 处于合法取值区间，且等待阶段（Waiting Phase）必须具备有效的绝对唤醒时间点（wakeAt）。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class RestoredStateValidator {
    private RestoredStateValidator() {
    }

    /**
     * 对反序列化恢复的 MachineState 进行严格的拓扑与状态自洽性校验。
     *
     * @param definition 编译期流程定义
     * @param state      待恢复的状态机快照对象
     * @throws DurableException 当拓扑不匹配或快照损坏时抛出
     */
    public static void validate(DurablePlanCompiler.Definition definition,
                         DurableState.MachineState state) {

        List<DurableState.RuntimeFrame> frames = state.frames;
        if (frames.isEmpty()) {
            if (state.lifecycle == DurableLifecycle.ACTIVE) {
                throw frame("ACTIVE snapshot requires at least one frame");
            }
            return;
        }
        // 根帧必须为计划根
        if (frames.get(0).node != definition.root()) {
            throw frame("Restored root frame does not match plan root: "
                    + frames.get(0).node.descriptor().path());
        }
        for (int index = 0; index < frames.size(); index++) {
            DurableState.RuntimeFrame frame = frames.get(index);
            String path = frame.node.descriptor().path();
            boolean leaf = frame.node instanceof DurablePlanNode.Invoke
                    || frame.node instanceof DurablePlanNode.Complete
                    || frame.node instanceof DurablePlanNode.Await
                    || frame.node instanceof DurablePlanNode.Parallel;
            if (index == frames.size() - 1) {
                validateLeafPhase(frame, path);
                continue;
            }
            if (leaf) {
                throw frame("Leaf frame is not on top of the restored stack: " + path);
            }
            validateParent(frame, frames.get(index + 1), path, index);
        }
    }

    private static void validateLeafPhase(DurableState.RuntimeFrame frame, String path) {
        DurablePlanNode node = frame.node;
        if (node instanceof DurablePlanNode.Sequence) {
            if (frame.phase != 1 && frame.phase != 0) {
                throw frame("Sequence frame phase invalid at " + path);
            }
            if (frame.phase == 1) {
                requireIndex(frame, ((DurablePlanNode.Sequence) node).children().size(), path);
            }
            return;
        }
        if (node instanceof DurablePlanNode.Route) {
            // phase=0：尚未进入（初始快照或父帧压入本帧后立即提交的检查点），
            // 恢复后由 enterRoute 正常进入；phase=1/2 表示子帧在栈中、本帧不应位于栈顶，
            // 此时的游标校验仅防御异常快照。
            if (frame.phase != 0 && frame.phase != 1 && frame.phase != 2) {
                throw frame("Route frame phase invalid at " + path);
            }
            if (frame.phase == 2) {
                validateRouteCursor(frame, (DurablePlanNode.Route) node, path);
            }
            return;
        }
        if (node instanceof DurablePlanNode.Fallback) {
            // phase=0 允许：初始快照或父帧压入后、首分支执行前崩溃；恢复后
            // enterFallback 会重置 index=0 并压入首分支，故 phase=0 不校验游标。
            if (frame.phase != 0 && frame.phase != 1) {
                throw frame("Fallback frame phase invalid at " + path);
            }
            if (frame.phase == 1) {
                requireIndex(frame, ((DurablePlanNode.Fallback) node).branches().size(), path);
            }
            return;
        }
        if (node instanceof DurablePlanNode.Control) {
            validateControlFrame(frame, (DurablePlanNode.Control) node, path);
            return;
        }
        if (node instanceof DurablePlanNode.Parallel) {
            // phase=0 允许：初始快照或父帧压入后、首分支执行前崩溃
            // （此时 branchOutcomes 全空）；恢复后 runParallel 从首个空槽位继续。
            if (frame.phase != 0 && frame.phase != 1) {
                throw frame("Parallel frame phase invalid at " + path);
            }
            return;
        }
        if (node instanceof DurablePlanNode.Invoke
                || node instanceof DurablePlanNode.Complete
                || node instanceof DurablePlanNode.Await) {
            if (frame.phase != 0) {
                throw frame("Leaf frame phase must be zero at " + path);
            }
            return;
        }
        throw frame("Unknown frame node at " + path);
    }

    /**
     * Route phase=2 的显式区间校验：index 必须满足 {@code -1 <= index < cases}。
     *
     * <p>机器实际会落库的 phase=2 形态只有两种：命中分支（index ∈ [0, cases)，
     * selected="case:N"，分支子帧在栈顶）或 otherwise 已选（index == -1 且
     * otherwise != null，selected="otherwise"，otherwise 子帧在栈顶）。两者作为
     * 栈中父帧时由 {@link #validateParent} 逐字段核对子帧与 selected；栈顶出现
     * phase=2 本身即异常快照，这里只保证游标数值自洽。</p>
     *
     * <p>index == -1 且 otherwise == null 只存在于 no-match 完成瞬间——
     * reduceRoute 直接以 Skipped(NO_ROUTE) 完成并弹帧，该瞬间不会落库；
     * 防御性放行，正常恢复驱动不会执行到该形态。</p>
     */
    private static void validateRouteCursor(DurableState.RuntimeFrame frame,
                                            DurablePlanNode.Route route, String path) {
        int cases = route.cases().size();
        if (frame.index < -1 || frame.index >= cases) {
            throw frame("Route frame index out of range at " + path);
        }
    }

    private static void validateParent(DurableState.RuntimeFrame parent,
                                       DurableState.RuntimeFrame child,
                                       String path, int stackIndex) {
        DurablePlanNode node = parent.node;
        if (node instanceof DurablePlanNode.Sequence) {
            DurablePlanNode.Sequence sequence = (DurablePlanNode.Sequence) node;
            if (parent.phase != 1) {
                throw frame("Parent Sequence phase must be 1 at " + path);
            }
            if (parent.index < 0 || parent.index >= sequence.children().size()
                    || sequence.children().get(parent.index) != child.node) {
                throw frame("Restored child does not match Sequence cursor at " + path);
            }
            return;
        }
        if (node instanceof DurablePlanNode.Route) {
            DurablePlanNode.Route route = (DurablePlanNode.Route) node;
            DurablePlanNode expected;
            if (parent.phase == 1) {
                expected = route.selector();
            } else if (parent.phase == 2) {
                if (parent.index >= 0 && parent.index < route.cases().size()) {
                    expected = route.cases().get(parent.index).branch();
                } else if (parent.index == -1 && route.otherwise() != null
                        && "otherwise".equals(parent.selected)) {
                    expected = route.otherwise();
                } else {
                    throw frame("Route cursor points nowhere at " + path);
                }
            } else {
                throw frame("Route frame phase invalid at " + path);
            }
            if (expected != child.node) {
                throw frame("Restored child does not match Route cursor at " + path);
            }
            return;
        }
        if (node instanceof DurablePlanNode.Fallback) {
            DurablePlanNode.Fallback fallback = (DurablePlanNode.Fallback) node;
            if (parent.phase != 1) {
                throw frame("Parent Fallback phase must be 1 at " + path);
            }
            if (parent.index < 0 || parent.index >= fallback.branches().size()
                    || fallback.branches().get(parent.index) != child.node) {
                throw frame("Restored child does not match Fallback cursor at " + path);
            }
            return;
        }
        if (node instanceof DurablePlanNode.Control) {
            DurablePlanNode.Control control = (DurablePlanNode.Control) node;
            if (child.node != control.body()) {
                throw frame("Restored child is not Control body at " + path);
            }
            boolean waiting = parent.phase == 2 || parent.phase == 3;
            if (waiting && parent.wake == null) {
                throw frame("Waiting control frame requires an absolute wake at " + path);
            }
            return;
        }
        throw frame("Node cannot be a parent frame at " + path);
    }

    private static void validateControlFrame(DurableState.RuntimeFrame frame,
                                             DurablePlanNode.Control control, String path) {
        switch (control.kind()) {
            case TIMEOUT:
                if (frame.phase != 0 && frame.phase != 1) {
                    throw frame("Timeout frame phase invalid at " + path);
                }
                return;
            case POLICY:
                if (frame.phase != 0 && frame.phase != 1) {
                    throw frame("Policy frame phase invalid at " + path);
                }
                return;
            case PERSISTENT_POLICY:
                if (frame.phase < 0 || frame.phase > 3) {
                    throw frame("PersistentPolicy frame phase invalid at " + path);
                }
                if ((frame.phase == 2 || frame.phase == 3) && frame.wake == null) {
                    throw frame("Waiting persistent policy frame requires wake at " + path);
                }
                return;
            default:
                throw frame("Unknown control kind at " + path);
        }
    }

    private static void requireIndex(DurableState.RuntimeFrame frame, int size, String path) {
        if (frame.index < 0 || frame.index >= size) {
            throw frame("Frame cursor index out of range at " + path);
        }
    }

    private static DurableException frame(String message) {
        return SnapshotCodec.frameMismatch(message);
    }
}
