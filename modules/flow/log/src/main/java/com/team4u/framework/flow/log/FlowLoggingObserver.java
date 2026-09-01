package com.team4u.framework.flow.log;

import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Metadata;
import com.team4u.framework.flow.spi.NodeDescriptor;
import com.team4u.framework.log.Loggers;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 流程结构化日志与执行树观察者（Flow Logging Observer）。
 *
 * <p>核心能力：
 * <ul>
 *   <li><b>单步实时结构化日志</b>：在步骤开始与完成时，通过 {@link Loggers} 实时输出动作、状态、耗时与脱敏后的上下文数据；</li>
 *   <li><b>终态 ASCII 执行树汇总</b>：在流程结束时统一生成带层级、耗时与四态结果的执行树，并输出最终脱敏上下文；</li>
 *   <li><b>全链路一致的脱敏管道</b>：单步日志与最终树统一基于 {@link ContextFormatter}，享受 {@link TraceContext} 白名单与 {@link com.team4u.framework.mask.Mask} 掩码保护；</li>
 *   <li><b>线程安全与并发兼容</b>：支持多线程并行分支与异步驱动。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
@Getter
@Setter
@Accessors(chain = true, fluent = true)
public class FlowLoggingObserver implements FlowObserver {

    private final String loggerNamePrefix;
    private final ContextFormatter contextFormatter;
    private final Supplier<Object> contextSupplier;
    private final boolean printStepLogs;
    private final boolean printTreeSummary;

    private static final class ExecutionTrace {
        private final TraceNode rootTraceNode;
        private final ConcurrentHashMap<String, TraceNode> nodeMap = new ConcurrentHashMap<String, TraceNode>();
        private final long startTime;

        ExecutionTrace(TraceNode rootTraceNode) {
            this.rootTraceNode = rootTraceNode;
            this.startTime = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, ExecutionTrace> activeTraces = new ConcurrentHashMap<String, ExecutionTrace>();
    private volatile TraceNode lastCompletedRootNode;

    public FlowLoggingObserver() {
        this(builder());
    }

    public FlowLoggingObserver(Builder builder) {
        this.loggerNamePrefix = builder.loggerNamePrefix != null ? builder.loggerNamePrefix : "flow.trace";
        this.contextFormatter = builder.contextFormatter != null
                ? builder.contextFormatter
                : new ContextFormatter(builder.contextProjector != null ? builder.contextProjector : AnnotatedContextProjector.INSTANCE);
        this.contextSupplier = builder.contextSupplier != null ? builder.contextSupplier : FlowContextHolder::get;
        this.printStepLogs = builder.printStepLogs;
        this.printTreeSummary = builder.printTreeSummary;
    }

    /**
     * 获取最近一次已完成执行的根追踪节点（主要用于单测与断言验证）。
     *
     * @return 根追踪节点，未完成则为 null
     */
    public TraceNode rootTraceNode() {
        return lastCompletedRootNode;
    }

    /**
     * 获取指定执行 ID 正在进行或已缓存的根追踪节点。
     *
     * @param executionId 执行唯一标识
     * @return 根追踪节点
     */
    public TraceNode rootTraceNode(String executionId) {
        ExecutionTrace trace = activeTraces.get(executionId);
        return trace != null ? trace.rootTraceNode : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String loggerNamePrefix = "flow.trace";
        private ContextFormatter contextFormatter;
        private ContextProjector contextProjector = AnnotatedContextProjector.INSTANCE;
        private Supplier<Object> contextSupplier = FlowContextHolder::get;
        private boolean printStepLogs = true;
        private boolean printTreeSummary = true;

        public Builder loggerNamePrefix(String prefix) {
            this.loggerNamePrefix = prefix;
            return this;
        }

        public Builder contextFormatter(ContextFormatter formatter) {
            this.contextFormatter = formatter;
            return this;
        }

        public Builder contextProjector(ContextProjector projector) {
            this.contextProjector = projector;
            return this;
        }

        public Builder contextSupplier(Supplier<Object> supplier) {
            this.contextSupplier = supplier;
            return this;
        }

        public Builder printStepLogs(boolean printStepLogs) {
            this.printStepLogs = printStepLogs;
            return this;
        }

        public Builder printTreeSummary(boolean printTreeSummary) {
            this.printTreeSummary = printTreeSummary;
            return this;
        }

        public FlowLoggingObserver build() {
            return new FlowLoggingObserver(this);
        }
    }

    @Override
    public void onEvent(Event event) {
        Metadata meta = event.metadata();
        String loggerName = loggerNamePrefix + "." + meta.flowId();
        String label = resolveNodeDisplayLabel(event);
        Object currentContext = event.payload() != null
                ? event.payload()
                : (contextSupplier != null ? contextSupplier.get() : null);

        switch (event.type()) {
            case FLOW_STARTED:
                onFlowStarted(meta, loggerName, currentContext);
                break;
            case NODE_STARTED:
            case PARALLEL_STARTED:
                onNodeStarted(event, meta, loggerName, label, currentContext);
                break;
            case NODE_COMPLETED:
                onNodeCompleted(event, meta, loggerName, label, currentContext);
                break;
            case ROUTE_SELECTED:
                onRouteSelected(event, meta);
                break;
            case FALLBACK_SELECTED:
                onFallbackSelected(event, meta);
                break;
            case FLOW_COMPLETED:
            case FLOW_CANCELLED:
            case FLOW_SUSPENDED:
                onFlowFinished(event, meta);
                break;
            default:
                break;
        }
    }

    private void onFlowStarted(Metadata meta, String loggerName, Object context) {
        String path = meta.nodePath();
        TraceNode root = new TraceNode(path, "flow: " + meta.flowId());
        root.setStartTime(System.currentTimeMillis());

        ExecutionTrace newTrace = new ExecutionTrace(root);
        newTrace.nodeMap.put(path, root);
        activeTraces.put(meta.executionId(), newTrace);

        if (printStepLogs) {
            Loggers.of(loggerName)
                    .action("FLOW_STARTED")
                    .status("start")
                    .atInfo()
                    .put("execId", meta.executionId())
                    .put("context", contextFormatter.format(context))
                    .log();
        }
    }

    private void onNodeStarted(Event event, Metadata meta, String loggerName, String label, Object context) {
        String path = meta.nodePath();
        ExecutionTrace trace = getOrCreateTrace(meta);

        if (!path.equals("$") && (trace.rootTraceNode == null || !path.equals(trace.rootTraceNode.getPath()))) {
            TraceNode startNode = new TraceNode(path, label);
            startNode.setStartTime(System.currentTimeMillis());
            trace.nodeMap.put(path, startNode);
            linkParent(trace, path, startNode);
        }

        if (printStepLogs) {
            Loggers.of(loggerName)
                    .action(event.type().name())
                    .status("start")
                    .atInfo()
                    .put("execId", meta.executionId())
                    .put("path", path)
                    .put("label", label)
                    .put("context", contextFormatter.format(context))
                    .log();
        }
    }

    private void onNodeCompleted(Event event, Metadata meta, String loggerName, String label, Object context) {
        String path = meta.nodePath();
        ExecutionTrace trace = activeTraces.get(meta.executionId());
        TraceNode completedNode = trace != null ? trace.nodeMap.get(path) : null;
        long duration = completedNode != null ? System.currentTimeMillis() - completedNode.getStartTime() : 0;
        String outcome = event.attributes().getOrDefault("outcome", "ACCEPTED");

        if (completedNode != null) {
            completedNode.setDurationMs(duration);
            completedNode.setOutcome(outcome);
            if (event.attributes().containsKey("attempt")) {
                completedNode.setExtra("attempt=" + event.attributes().get("attempt"));
            }
        }

        if (printStepLogs) {
            logStepCompletion(loggerName, meta.executionId(), path, label, duration, outcome, context);
        }
    }

    private void logStepCompletion(String loggerName, String execId, String path, String label,
                                   long duration, String outcome, Object context) {
        Loggers stepLogger = Loggers.of(loggerName)
                .action("NODE_COMPLETED")
                .put("execId", execId)
                .put("path", path)
                .put("label", label)
                .put("duration", duration + "ms")
                .put("outcome", outcome);

        if ("ACCEPTED".equalsIgnoreCase(outcome)) {
            stepLogger.success();
        } else if ("REJECTED".equalsIgnoreCase(outcome) || "SKIPPED".equalsIgnoreCase(outcome)) {
            stepLogger.status(outcome.toLowerCase()).atInfo();
        } else {
            stepLogger.status("failed").atError();
        }

        stepLogger.put("context", contextFormatter.format(context)).log();
    }

    private void onRouteSelected(Event event, Metadata meta) {
        ExecutionTrace trace = activeTraces.get(meta.executionId());
        if (trace != null) {
            TraceNode routeNode = trace.nodeMap.get(meta.nodePath());
            if (routeNode != null) {
                routeNode.setExtra("selected=" + event.attributes().getOrDefault("branch", "unknown"));
            }
        }
    }

    private void onFallbackSelected(Event event, Metadata meta) {
        ExecutionTrace trace = activeTraces.get(meta.executionId());
        if (trace != null) {
            TraceNode fallbackNode = trace.nodeMap.get(meta.nodePath());
            if (fallbackNode != null) {
                fallbackNode.setExtra("fallback=" + event.attributes().getOrDefault("trigger", "unknown"));
            }
        }
    }

    private void onFlowFinished(Event event, Metadata meta) {
        ExecutionTrace trace = activeTraces.remove(meta.executionId());
        if (trace == null || trace.rootTraceNode == null) {
            return;
        }

        long totalDuration = System.currentTimeMillis() - trace.startTime;
        trace.rootTraceNode.setDurationMs(totalDuration);
        String finalOutcome = event.attributes().getOrDefault("outcome", event.type().name());
        trace.rootTraceNode.setOutcome(finalOutcome);
        this.lastCompletedRootNode = trace.rootTraceNode;

        if (printTreeSummary) {
            String treeStr = TraceTreeFormatter.formatTree(trace.rootTraceNode);
            Object finalContext = event.payload() != null
                    ? event.payload()
                    : (contextSupplier != null ? contextSupplier.get() : null);
            String maskedFinalContext = contextFormatter.format(finalContext);

            log.info("Flow Execution Summary [flowId={} | execId={} | total={}ms | outcome={}]\n{}\nFinal Context:\n{}",
                    meta.flowId(), meta.executionId(), totalDuration, finalOutcome, treeStr, maskedFinalContext);
        }
    }

    private ExecutionTrace getOrCreateTrace(Metadata meta) {
        return activeTraces.computeIfAbsent(meta.executionId(), k -> {
            TraceNode r = new TraceNode("$", "flow: " + meta.flowId());
            r.setStartTime(System.currentTimeMillis());
            ExecutionTrace t = new ExecutionTrace(r);
            t.nodeMap.put("$", r);
            return t;
        });
    }

    private void linkParent(ExecutionTrace trace, String path, TraceNode child) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            String parentPath = path.substring(0, lastSlash);
            TraceNode parent = trace.nodeMap.get(parentPath);
            if (parent != null) {
                parent.addChild(child);
                return;
            }
        }
        if (trace.rootTraceNode != null && !path.equals(trace.rootTraceNode.getPath())) {
            trace.rootTraceNode.addChild(child);
        }
    }

    private String resolveNodeDisplayLabel(Event event) {
        Metadata meta = event.metadata();
        if (meta.label().isPresent() && !meta.label().get().trim().isEmpty()) {
            return meta.label().get().trim();
        }
        NodeDescriptor desc = event.descriptor();
        if (desc != null) {
            if (desc.implementationClass().isPresent()) {
                Class<?> implClass = desc.implementationClass().get();
                if (!isSyntheticOrLambda(implClass)) {
                    String className = implClass.getSimpleName();
                    return desc.qualifier().isPresent() ? className + " (" + desc.qualifier().get() + ")" : className;
                }
            }
            if (desc.contractClass().isPresent()) {
                Class<?> contractClass = desc.contractClass().get();
                if (!isSyntheticOrLambda(contractClass)) {
                    String className = contractClass.getSimpleName();
                    return desc.qualifier().isPresent() ? className + " (" + desc.qualifier().get() + ")" : className;
                }
            }
            if (desc.kind() != null) {
                return desc.kind().name();
            }
        }
        return "<unnamed>";
    }

    private static boolean isSyntheticOrLambda(Class<?> clazz) {
        return clazz.isSynthetic() || clazz.isAnonymousClass() || clazz.getName().contains("$$Lambda") || clazz.getSimpleName().isEmpty();
    }
}
