package com.team4u.framework.flow.model;

/**
 * 流程引擎标准诊断码与失败原因常量规范。
 *
 * @author jay.wu
 */
public final class FlowDiagnosticCodes {
    private FlowDiagnosticCodes() { }

    // ====== 运行时超时与取消 ======
    /** 流程作用域截止时间到达超时。 */
    public static final String TIMEOUT = "TIMEOUT";
    /** 操作被取消。 */
    public static final String OPERATION_CANCELLED = "OPERATION_CANCELLED";
    /** 操作执行线程被中断。 */
    public static final String OPERATION_INTERRUPTED = "OPERATION_INTERRUPTED";
    /** 操作执行抛出未受检异常。 */
    public static final String OPERATION_EXCEPTION = "OPERATION_EXCEPTION";
    /** 适配器节点输入投影执行异常。 */
    public static final String ADAPTER_PROJECT_EXCEPTION = "ADAPTER_PROJECT_EXCEPTION";
    /** 适配器节点结果合并执行异常。 */
    public static final String ADAPTER_MERGE_EXCEPTION = "ADAPTER_MERGE_EXCEPTION";
    /** 线程池拒绝执行任务。 */
    public static final String EXECUTOR_REJECTED = "EXECUTOR_REJECTED";

    // ====== 路由与控制治理 ======
    /** 条件路由未匹配到任何分支且无 otherwise。 */
    public static final String NO_ROUTE = "NO_ROUTE";
    /** 路由分支键使用了数组类型（equals 为引用相等，无法可靠匹配）。 */
    public static final String ARRAY_ROUTE_KEY = "ARRAY_ROUTE_KEY";
    /** 策略退避等待时被中断。 */
    public static final String WAIT_INTERRUPTED = "WAIT_INTERRUPTED";
    /** 策略回调执行异常。 */
    public static final String POLICY_EXCEPTION = "POLICY_EXCEPTION";
    /** 并行汇聚策略执行异常。 */
    public static final String JOIN_EXCEPTION = "JOIN_EXCEPTION";

    // ====== 静态编译与拓扑校验 ======
    /** 重复节点标签。 */
    public static final String DUPLICATE_LABEL = "DUPLICATE_LABEL";
    /** 重复具名 Scope 名称。 */
    public static final String DUPLICATE_SCOPE = "DUPLICATE_SCOPE";
    /** 重复并行分支 Token。 */
    public static final String DUPLICATE_BRANCH = "DUPLICATE_BRANCH";
    /** 重复挂起点名称。 */
    public static final String DUPLICATE_RESUME_POINT = "DUPLICATE_RESUME_POINT";
    /** 重复拓扑路径。 */
    public static final String DUPLICATE_PATH = "DUPLICATE_PATH";
    /** 并行分支非法包含挂起点。 */
    public static final String PARALLEL_AWAIT = "PARALLEL_AWAIT";
    /** 并行分支非法包含持久化策略。 */
    public static final String PARALLEL_PERSISTENT_POLICY = "PARALLEL_PERSISTENT_POLICY";
    /** 组件绑定接口未实现目标标记。 */
    public static final String INVALID_BINDING = "INVALID_BINDING";
    /** 依赖组件解析失败（Bean 未找到）。 */
    public static final String MISSING_BINDING = "MISSING_BINDING";
    /** 依赖组件解析实例类型不匹配。 */
    public static final String BINDING_TYPE = "BINDING_TYPE";
    /** 无法获取组件实现类。 */
    public static final String IMPLEMENTATION_CLASS = "IMPLEMENTATION_CLASS";
}
