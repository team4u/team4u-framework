package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 分支选择节点实现。
 *
 * @author jay.wu
 */
final class ChooseNode implements FlowNode {

    private final String id;
    private final String path;
    private final String address;
    private final Function<Object, Object> selector;
    private final Map<Object, BranchTarget> branchTargets;
    private final BranchTarget otherwiseTarget;
    private final Function<Object, StopReason> otherwiseStopReason;

    private static final class BranchTarget {
        private final Flow<Object, Object> flow;
        private final String branchToken;
        private final String displayKey;

        BranchTarget(Flow<Object, Object> flow, String branchToken, String displayKey) {
            this.flow = flow;
            this.branchToken = branchToken;
            this.displayKey = displayKey;
        }
    }

    @SuppressWarnings("unchecked")
    ChooseNode(String id, String path, String address,
               Function<?, ?> selector,
               Map<?, ? extends Flow<?, ?>> branches,
               Flow<?, ?> otherwiseBranch,
               Function<?, StopReason> otherwiseStopReason) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = path != null ? path : id;
        this.address = address != null ? address : id;
        this.selector = (Function<Object, Object>) Objects.requireNonNull(selector, "selector must not be null");

        Map<Object, BranchTarget> bMap = new LinkedHashMap<>();
        if (branches != null) {
            Map<Object, String> displayLabels = generateDisplayLabels(branches.keySet());
            int branchIdx = 0;
            for (Map.Entry<?, ? extends Flow<?, ?>> entry : branches.entrySet()) {
                String branchToken = "/c:" + branchIdx;
                String displayKey = displayLabels.get(entry.getKey());
                bMap.put(entry.getKey(), new BranchTarget((Flow<Object, Object>) entry.getValue(), branchToken, displayKey));
                branchIdx++;
            }
        }
        this.branchTargets = Collections.unmodifiableMap(bMap);
        this.otherwiseTarget = otherwiseBranch != null
                ? new BranchTarget((Flow<Object, Object>) otherwiseBranch, "/c:otherwise", "otherwise")
                : null;
        this.otherwiseStopReason = (Function<Object, StopReason>) otherwiseStopReason;
    }

    private static Map<Object, String> generateDisplayLabels(Set<?> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Object> keyList = new ArrayList<>(keys);

        // Group keys by their String.valueOf()
        Map<String, List<Object>> byStringValue = new LinkedHashMap<>();
        for (Object key : keyList) {
            String strVal = String.valueOf(key);
            byStringValue.computeIfAbsent(strVal, k -> new ArrayList<>()).add(key);
        }

        Map<Object, String> candidateMap = new LinkedHashMap<>();

        for (Map.Entry<String, List<Object>> entry : byStringValue.entrySet()) {
            String strVal = entry.getKey();
            List<Object> group = entry.getValue();

            if (group.size() == 1) {
                // No collision on String.valueOf
                Object singleKey = group.get(0);
                candidateMap.put(singleKey, strVal);
            } else {
                // Collision on String.valueOf across different types or unequal keys of the same type
                Map<String, Set<Class<?>>> classesBySimpleName = new HashMap<>();
                for (Object key : group) {
                    Class<?> clazz = key.getClass();
                    classesBySimpleName.computeIfAbsent(clazz.getSimpleName(), k -> new HashSet<>()).add(clazz);
                }

                Map<Class<?>, Integer> classTotalCount = new HashMap<>();
                for (Object key : group) {
                    classTotalCount.put(key.getClass(), classTotalCount.getOrDefault(key.getClass(), 0) + 1);
                }
                Map<Class<?>, Integer> classSeenIndex = new HashMap<>();

                for (Object key : group) {
                    if (key instanceof String) {
                        // Keep ordinary String label unchanged where possible
                        candidateMap.put(key, (String) key);
                    } else {
                        Class<?> clazz = key.getClass();
                        String typeName = classesBySimpleName.get(clazz.getSimpleName()).size() > 1
                                ? clazz.getName()
                                : clazz.getSimpleName();

                        int seen = classSeenIndex.getOrDefault(clazz, 0) + 1;
                        classSeenIndex.put(clazz, seen);

                        int totalForClass = classTotalCount.get(clazz);
                        String label;
                        if (totalForClass > 1) {
                            label = strVal + " (" + typeName + "#" + seen + ")";
                        } else {
                            label = strVal + " (" + typeName + ")";
                        }
                        candidateMap.put(key, label);
                    }
                }
            }
        }

        // Final uniqueness check to ensure strictly unique deterministic labels within choose declaration
        Set<String> usedLabels = new HashSet<>();
        Map<Object, String> resultMap = new LinkedHashMap<>();

        for (Object key : keyList) {
            String candidate = candidateMap.get(key);
            String finalLabel = candidate;
            int counter = 2;
            while (usedLabels.contains(finalLabel)) {
                finalLabel = candidate + "#" + counter;
                counter++;
            }
            usedLabels.add(finalLabel);
            resultMap.put(key, finalLabel);
        }

        return resultMap;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String address() {
        return address;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.CHOOSE;
    }

    @Override
    public FlowResult<Object> execute(ExecutionContext context, Object input) throws Exception {
        long startNanos = System.nanoTime();
        String effectivePath = context.qualifyPath(path);

        context.notifyNodeStarted(id, effectivePath, NodeKind.CHOOSE);

        Object key;
        try {
            key = selector.apply(input);
            if (key == null) {
                throw new IllegalStateException("Choose selector returned null key for node [" + id + "]");
            }
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long duration = System.nanoTime() - startNanos;
            FailureContext failure = new FailureContext(id, effectivePath, t);
            if (context.isTraceEnabled()) {
                context.traceCollector().recordLeaf(id, effectivePath, null, NodeKind.CHOOSE,
                        FlowResult.Kind.FAILED, duration, null, null, failure);
            }
            context.notifyNodeCompleted(id, effectivePath, NodeKind.CHOOSE, FlowResult.Kind.FAILED, duration, null, failure);
            return FlowResult.failed(failure);
        }

        BranchTarget target = branchTargets.get(key);
        if (target != null) {
            return executeBranch(context, input, target, effectivePath, startNanos);
        }

        if (otherwiseTarget != null) {
            return executeBranch(context, input, otherwiseTarget, effectivePath, startNanos);
        }

        if (otherwiseStopReason != null) {
            try {
                StopReason reason = otherwiseStopReason.apply(input);
                if (reason == null) {
                    throw new IllegalStateException("Choose otherwiseStopReason returned null StopReason for node [" + id + "]");
                }
                long duration = System.nanoTime() - startNanos;
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, effectivePath, null, NodeKind.CHOOSE,
                            FlowResult.Kind.STOPPED, duration, String.valueOf(key), reason, null);
                }
                context.notifyNodeCompleted(id, effectivePath, NodeKind.CHOOSE, FlowResult.Kind.STOPPED, duration, reason, null);
                return FlowResult.stopped(reason);
            } catch (Throwable t) {
                if (t instanceof Error) {
                    throw (Error) t;
                }
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                long duration = System.nanoTime() - startNanos;
                FailureContext failure = new FailureContext(id, effectivePath, t);
                if (context.isTraceEnabled()) {
                    context.traceCollector().recordLeaf(id, effectivePath, null, NodeKind.CHOOSE,
                            FlowResult.Kind.FAILED, duration, String.valueOf(key), null, failure);
                }
                context.notifyNodeCompleted(id, effectivePath, NodeKind.CHOOSE, FlowResult.Kind.FAILED, duration, null, failure);
                return FlowResult.failed(failure);
            }
        }

        // Branch not matched and no otherwise
        long duration = System.nanoTime() - startNanos;
        NoSuchElementException noMatchEx = new NoSuchElementException("No branch matched key [" + key + "] for choose node [" + id + "]");
        FailureContext failure = new FailureContext(id, effectivePath, noMatchEx);
        if (context.isTraceEnabled()) {
            context.traceCollector().recordLeaf(id, effectivePath, null, NodeKind.CHOOSE,
                    FlowResult.Kind.FAILED, duration, String.valueOf(key), null, failure);
        }
        context.notifyNodeCompleted(id, effectivePath, NodeKind.CHOOSE, FlowResult.Kind.FAILED, duration, null, failure);
        return FlowResult.failed(failure);
    }

    private FlowResult<Object> executeBranch(ExecutionContext context, Object input, BranchTarget target,
                                             String effectivePath, long startNanos) throws Exception {
        if (context.isTraceEnabled()) {
            context.traceCollector().enterScope(id, effectivePath, null, NodeKind.CHOOSE, target.displayKey);
        }

        String branchAddress = address + target.branchToken;
        ExecutionContext childContext = context.childContext(path, branchAddress);

        FlowResult<Object> result;
        try {
            if (target.flow instanceof DefaultFlow) {
                result = ((DefaultFlow<Object, Object>) target.flow).rootNode().execute(childContext, input);
            } else {
                FlowExecution<Object> execution = target.flow.run(input, RunOptions.builder()
                        .executionId(context.executionId())
                        .trace(context.isTraceEnabled())
                        .observer(context.observer())
                        .build());
                result = execution.result();
            }
        } catch (Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            FailureContext failure = new FailureContext(id, effectivePath, t);
            result = FlowResult.failed(failure);
        }

        if (context.isTraceEnabled()) {
            context.traceCollector().exitScope(result.kind(),
                    result.isStopped() ? result.stopReason() : null,
                    result.isFailed() ? result.failure() : null);
        }

        long duration = System.nanoTime() - startNanos;
        context.notifyNodeCompleted(id, effectivePath, NodeKind.CHOOSE, result.kind(), duration,
                result.isStopped() ? result.stopReason() : null,
                result.isFailed() ? result.failure() : null);
        return result;
    }

    @Override
    public NodeDescription describe() {
        List<String> keys = new ArrayList<>();
        Map<String, FlowDescription> branchDescMap = new LinkedHashMap<>();
        for (Map.Entry<Object, BranchTarget> entry : branchTargets.entrySet()) {
            String kStr = entry.getValue().displayKey;
            keys.add(kStr);
            branchDescMap.put(kStr, entry.getValue().flow.describe());
        }
        FlowDescription otherwiseDesc = otherwiseTarget != null ? otherwiseTarget.flow.describe() : null;

        return new NodeDescription(id, path, address, NodeKind.CHOOSE, keys, branchDescMap,
                otherwiseDesc, otherwiseStopReason != null, null, null, null, null);
    }

    @Override
    public <R> R project(Flow.Projection<R> projection) {
        List<Object> keys = new ArrayList<>(branchTargets.keySet());
        Flow.ChooseInfo<Object> info = new Flow.ChooseInfo<>(id, path, address, keys,
                otherwiseTarget != null, otherwiseStopReason != null);

        Map<Object, R> branchProjections = new LinkedHashMap<>();
        for (Map.Entry<Object, BranchTarget> entry : branchTargets.entrySet()) {
            branchProjections.put(entry.getKey(), entry.getValue().flow.project(projection));
        }
        R otherwiseProj = otherwiseTarget != null ? otherwiseTarget.flow.project(projection) : null;

        return projection.projectChoose(info, selector, branchProjections, otherwiseProj, otherwiseStopReason);
    }
}
