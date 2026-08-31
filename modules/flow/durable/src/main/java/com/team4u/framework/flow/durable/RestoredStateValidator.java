package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.ControlKind;

import java.util.List;

/**
 * 恢复态防御校验：帧栈结构必须与计划拓扑一致（父帧待完成的子帧真实存在、
 * phase 与节点类型匹配、parallel 分支不含挂起语义等），否则拒绝恢复。
 *
 * <p>phase=0 的结构帧（Sequence/Route/Fallback/Parallel）是合法栈顶：start 的
 * 初始快照（revision=1）与「sequence 归约压入下一子节点后立即提交」的检查点都会
 * 产生该形态，恢复后由 DurableMachine 的 enterXxx 正常进入（机器侧对 phase!=0
 * 的防御性检查与本校验互补）。</p>
 */
final class RestoredStateValidator {
    private RestoredStateValidator() {
    }

    static void validate(DurablePlanCompiler.Definition definition,
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
            case RETRY:
                if (frame.phase != 0 && frame.phase != 1 && frame.phase != 2) {
                    throw frame("Retry frame phase invalid at " + path);
                }
                if (frame.phase == 2 && frame.wake == null) {
                    throw frame("Waiting retry frame requires wake at " + path);
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
