package com.team4u.framework.flow;

/**
 * 流程结构描述访问者 SPI（覆盖 Invoke、Sequence、Route、Fallback、Parallel、Await、Control、Complete 节点）。
 *
 * <p>基于访问者模式（Visitor Pattern）对 {@link NodeDescription} 树进行遍历，
 * 常用于构建图表渲染引擎（Mermaid/Graphviz）、文本打印器、合规审计工具等。</p>
 *
 * @param <R> 访问遍历的返回值类型
 * @author jay.wu
 */
public interface FlowVisitor<R> {

    /**
     * 访问原子操作调用节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitInvoke(NodeDescription node);

    /**
     * 访问顺序流水线/作用域节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitSequence(NodeDescription node);

    /**
     * 访问条件路由节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitRoute(NodeDescription node);

    /**
     * 访问降级恢复节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitFallback(NodeDescription node);

    /**
     * 访问并行并发节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitParallel(NodeDescription node);

    /**
     * 访问挂起等待节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitAwait(NodeDescription node);

    /**
     * 访问环绕治理控制节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitControl(NodeDescription node);

    /**
     * 访问常量/恒等终态节点。
     *
     * @param node 节点描述
     * @return 访问结果
     */
    R visitComplete(NodeDescription node);
}

