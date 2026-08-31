package com.team4u.framework.flow;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 公开、窄型、只读、与持久化无关的可执行投影 SPI 访问者。
 * 遍历已由 Compiler 校验和解析后的 PlanNode 树，向外部暴露全部强类型执行合同与拓扑。
 * 各 visit 方法必须返回非 null 的投影结果对象。
 *
 * @param <R> 访问者投影产物的目标类型（非 null）
 */
public interface ExecutableFlowVisitor<R> {

    R visitInvoke(NodeDescriptor descriptor,
                  ExecutableBinding binding,
                  Function<Object, Object> project,
                  BiFunction<Object, Object, Object> merge);

    R visitSequence(NodeDescriptor descriptor,
                    List<R> children,
                    Optional<String> scopeName);

    R visitRoute(NodeDescriptor descriptor,
                 ExecutableBinding selectorBinding,
                 List<ExecutableRouteCase<R>> cases,
                 Optional<R> otherwise);

    R visitFallback(NodeDescriptor descriptor,
                    FallbackTrigger trigger,
                    List<R> branches);

    R visitParallel(NodeDescriptor descriptor,
                    List<ExecutableParallelBranch<R>> branches,
                    JoinStrategy<?> join);

    R visitAwait(NodeDescriptor descriptor,
                 ResumePoint<?> resumePoint);

    R visitControl(NodeDescriptor descriptor,
                   ControlKind kind,
                   R body,
                   Optional<ExecutableBinding> binding,
                   Function<Object, Object> keyProjection,
                   Object configuration);

    R visitComplete(NodeDescriptor descriptor,
                    Outcome<?> outcome,
                    boolean identity);
}
