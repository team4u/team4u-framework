package com.team4u.framework.flow;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 不可变、类型安全、可重复执行的业务流程定义。
 *
 * @param <I> 流程输入类型
 * @param <O> 流程成功输出类型
 * @author jay.wu
 */
public interface Flow<I, O> {

    /**
     * 流程唯一标识。
     */
    String id();

    /**
     * 同步执行流程，成功时返回输出值；业务停止或技术失败时抛出统一的 {@link FlowRunException}。
     *
     * @param input 业务输入，非 null
     * @return 业务成功输出，非 null
     * @throws FlowRunException 当流程进入 STOPPED 或 FAILED 状态时抛出
     */
    O call(I input);

    /**
     * 使用默认选项同步执行流程，返回包含结果与诊断信息的 {@link FlowExecution}。
     *
     * @param input 业务输入，非 null
     * @return 流程执行句柄
     */
    default FlowExecution<O> run(I input) {
        return run(input, RunOptions.defaults());
    }

    /**
     * 使用指定选项同步执行流程，返回包含结果与诊断信息的 {@link FlowExecution}。
     *
     * @param input   业务输入，非 null
     * @param options 运行选项（可配置 executionId、trace、observer 等）
     * @return 流程执行句柄
     */
    FlowExecution<O> run(I input, RunOptions options);

    /**
     * 获取流程的只读结构描述模型（面向 Graph 渲染与 Test 校验）。
     *
     * @return 结构描述
     */
    FlowDescription describe();

    /**
     * 使用投影 SPI 遍历流程内部逻辑树。
     *
     * @param projection 投影器实例
     * @param <R>        投影结果类型
     * @return 投影产物
     */
    <R> R project(Projection<R> projection);

    /**
     * 面向执行引擎与持久化恢复的高级投影访问 SPI。
     *
     * @param <R> 节点投影产物类型
     */
    interface Projection<R> {

        R projectSequence(SequenceInfo info, List<R> children);

        <T, R1> R projectStep(StepInfo info, Step<T, R1> step, Step.Contextual<T, R1> contextualStep, List<StepInterceptor> interceptors);

        <T> R projectTap(TapInfo info, Action<T> action, Action.Contextual<T> contextualAction, List<StepInterceptor> interceptors);

        <T> R projectGuard(GuardInfo info, Condition<T> condition, Function<T, StopReason> reasonFactory);

        <T, K, R1> R projectChoose(ChooseInfo<K> info, Function<T, K> selector, Map<K, R> branches, R otherwiseBranch, Function<T, StopReason> otherwiseStopReason);

        <T, R1> R projectSubflow(SubflowInfo info, Flow<T, R1> subflow, R subflowProjection);

        <T, R1> R projectRecover(RecoverInfo info, R body, Recovery<T, R1> recovery, Recovery.Contextual<T, R1> contextualRecovery);

        <T, R1> R projectEnsure(EnsureInfo info, R body, CompletionAction<T, R1> completionAction, CompletionAction.Contextual<T, R1> contextualCompletionAction);
    }

    final class SequenceInfo {
        private final String id;
        private final String path;
        private final String address;

        public SequenceInfo(String id, String path, String address) {
            this.id = id;
            this.path = path;
            this.address = address;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
    }

    final class StepInfo {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;

        public StepInfo(String id, String path, String address, boolean contextual) {
            this.id = id;
            this.path = path;
            this.address = address;
            this.contextual = contextual;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
        public boolean isContextual() { return contextual; }
    }

    final class TapInfo {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;

        public TapInfo(String id, String path, String address, boolean contextual) {
            this.id = id;
            this.path = path;
            this.address = address;
            this.contextual = contextual;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
        public boolean isContextual() { return contextual; }
    }

    final class GuardInfo {
        private final String id;
        private final String path;
        private final String address;

        public GuardInfo(String id, String path, String address) {
            this.id = id;
            this.path = path;
            this.address = address;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
    }

    final class ChooseInfo<K> {
        private final String id;
        private final String path;
        private final String address;
        private final List<K> branchKeys;
        private final boolean hasOtherwise;
        private final boolean hasOtherwiseStop;

        public ChooseInfo(String id, String path, String address, List<K> branchKeys, boolean hasOtherwise, boolean hasOtherwiseStop) {
            this.id = id;
            this.path = path;
            this.address = address;
            this.branchKeys = branchKeys;
            this.hasOtherwise = hasOtherwise;
            this.hasOtherwiseStop = hasOtherwiseStop;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
        public List<K> branchKeys() { return branchKeys; }
        public boolean hasOtherwise() { return hasOtherwise; }
        public boolean hasOtherwiseStop() { return hasOtherwiseStop; }
    }

    final class SubflowInfo {
        private final String id;
        private final String path;
        private final String address;
        private final String subflowId;

        public SubflowInfo(String id, String path, String address, String subflowId) {
            this.id = id;
            this.path = path;
            this.address = address;
            this.subflowId = subflowId;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
        public String subflowId() { return subflowId; }
    }

    final class RecoverInfo {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;

        public RecoverInfo(String id, String path, String address, boolean contextual) {
            this.id = id;
            this.path = path;
            this.address = address;
            this.contextual = contextual;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
        public boolean isContextual() { return contextual; }
    }

    final class EnsureInfo {
        private final String id;
        private final String path;
        private final String address;
        private final boolean contextual;

        public EnsureInfo(String id, String path, String address, boolean contextual) {
            this.id = id;
            this.path = path;
            this.address = address;
            this.contextual = contextual;
        }

        public String id() { return id; }
        public String path() { return path; }
        public String address() { return address; }
        public boolean isContextual() { return contextual; }
    }
}
