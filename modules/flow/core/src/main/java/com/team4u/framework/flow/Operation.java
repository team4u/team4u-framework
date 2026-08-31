package com.team4u.framework.flow;

/**
 * 流程编排中的原子业务步骤核心执行接口 SPI。
 *
 * <p>设计原则与规范：
 * <ul>
 *   <li><b>无状态与线程安全</b>：Operation 实现应设计为无状态且线程安全的单例/组件，避免跨调用共享内部可变字段；</li>
 *   <li><b>四态结果返回</b>：方法返回 {@link Outcome} 代数类型（Accepted/Rejected/Skipped/Failed），业务拒绝或跳过应优先返回对应 Outcome 而非抛异常；</li>
 *   <li><b>异常安全机制</b>：方法声明抛出 {@link Exception}，任何抛出的检查型/运行时异常都将被引擎捕获并统一转换为 {@link Outcome.Failed}；</li>
 *   <li><b>上下文协作</b>：通过传入的 {@link OperationContext} 可读取节点路径元数据、幂等调用标识、协作式取消信号以及执行轻量级协作挂起。</li>
 * </ul>
 * </p>
 *
 * @param <I> 输入数据类型
 * @param <O> 输出数据类型
 * @author jay.wu
 */
@FunctionalInterface
public interface Operation<I, O> {

    /**
     * 执行原子业务操作。
     *
     * @param context 当前步骤执行的上下文信息（元数据、取消信号、幂等标识等），保证非 null
     * @param input   输入数据载荷，保证非 null
     * @return 业务执行的四态结果（不能返回 null）
     * @throws Exception 业务执行期间发生的任何受检或未受检异常
     */
    Outcome<O> execute(OperationContext context, I input) throws Exception;
}

