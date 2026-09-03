package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.parser.SourceSpan;

import com.team4u.framework.flow.compiler.FlowPaths;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.definition.model.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编译器路径到 DSL 源码位置映射构建器（Source Map Builder）。
 *
 * <p>根据 Flow 逻辑 AST 拓扑递归建立每一个节点路径（Compiler Path）到 AST 规范位置（{@link SourceSpan}）的映射，
 * 并提供基于路径前缀的回退查找能力。</p>
 *
 * @author jay.wu
 */
public final class SourceMapBuilder {

    private SourceMapBuilder() {
    }

    /**
     * 构建编译路径到源码定位信息的映射字典。
     *
     * @param rootNode 流程逻辑根节点
     * @param rootSpec 流程定义规范根节点
     * @return 路径到 SourceSpan 的映射字典
     */
    public static Map<String, SourceSpan> build(Logical rootNode, FlowSpec rootSpec) {
        Map<String, SourceSpan> sourceMap = new LinkedHashMap<String, SourceSpan>();
        buildRecursive(rootNode, FlowPaths.root(), rootSpec, sourceMap);
        return sourceMap;
    }

    /**
     * 根据节点路径查找源码定位信息（支持逐级向上父路径回退匹配）。
     *
     * @param sourceMap   映射字典
     * @param path        节点路径
     * @param defaultSpan 默认回退定位
     * @return 匹配的 SourceSpan
     */
    public static SourceSpan findSourceSpan(
            Map<String, SourceSpan> sourceMap,
            String path,
            SourceSpan defaultSpan) {
        if (path == null || sourceMap == null) {
            return defaultSpan;
        }
        SourceSpan exact = sourceMap.get(path);
        if (exact != null) {
            return exact;
        }
        // 回退为前缀匹配
        String current = path;
        while (current.contains("/")) {
            current = current.substring(0, current.lastIndexOf('/'));
            SourceSpan candidate = sourceMap.get(current);
            if (candidate != null) {
                return candidate;
            }
        }
        return defaultSpan;
    }

    private static void buildRecursive(
            Logical node,
            String path,
            FlowSpec spec,
            Map<String, SourceSpan> sourceMap) {
        if (node == null || spec == null) {
            return;
        }
        sourceMap.put(path, spec.span());

        if (node instanceof Logical.Sequence && spec instanceof SequenceSpec) {
            Logical.Sequence seqNode = (Logical.Sequence) node;
            SequenceSpec seqSpec = (SequenceSpec) spec;
            for (int i = 0; i < seqNode.children().size(); i++) {
                FlowSpec childSpec = i < seqSpec.elements().size() ? seqSpec.elements().get(i) : spec;
                buildRecursive(seqNode.children().get(i), FlowPaths.child(path, i), childSpec, sourceMap);
            }
        } else if (node instanceof Logical.Route && spec instanceof RouteSpec) {
            Logical.Route routeNode = (Logical.Route) node;
            RouteSpec routeSpec = (RouteSpec) spec;
            sourceMap.put(FlowPaths.selectorPath(path), routeSpec.selector().span());
            for (int i = 0; i < routeNode.cases().size(); i++) {
                CaseSpec caseSpec = i < routeSpec.cases().size() ? routeSpec.cases().get(i) : null;
                FlowSpec branchSpec = caseSpec != null ? caseSpec.branch() : spec;
                buildRecursive(routeNode.cases().get(i).branch(), FlowPaths.routeCase(path, i), branchSpec, sourceMap);
            }
            if (routeNode.otherwise() != null && routeSpec.otherwise() != null) {
                buildRecursive(routeNode.otherwise(), FlowPaths.routeOtherwise(path), routeSpec.otherwise(), sourceMap);
            }
        } else if (node instanceof Logical.Fallback && spec instanceof FirstApplicableSpec) {
            Logical.Fallback fbNode = (Logical.Fallback) node;
            FirstApplicableSpec faSpec = (FirstApplicableSpec) spec;
            for (int i = 0; i < fbNode.branches().size(); i++) {
                FlowSpec childSpec = i < faSpec.branches().size() ? faSpec.branches().get(i) : spec;
                buildRecursive(fbNode.branches().get(i), FlowPaths.fallbackBranch(path, i), childSpec, sourceMap);
            }
        } else if (node instanceof Logical.Fallback && spec instanceof RecoverSpec) {
            Logical.Fallback fbNode = (Logical.Fallback) node;
            RecoverSpec recSpec = (RecoverSpec) spec;
            if (fbNode.branches().size() >= 2) {
                buildRecursive(fbNode.branches().get(0), FlowPaths.fallbackBranch(path, 0), recSpec.body(), sourceMap);
                buildRecursive(fbNode.branches().get(1), FlowPaths.fallbackBranch(path, 1), recSpec.onFailure(), sourceMap);
            }
        } else if (node instanceof Logical.Parallel && spec instanceof ParallelSpec) {
            Logical.Parallel parNode = (Logical.Parallel) node;
            ParallelSpec parSpec = (ParallelSpec) spec;
            for (int i = 0; i < parNode.branches().size(); i++) {
                BranchSpec branchSpec = i < parSpec.branches().size() ? parSpec.branches().get(i) : null;
                FlowSpec branchFlowSpec = branchSpec != null ? branchSpec.flow() : spec;
                buildRecursive(parNode.branches().get(i).flow(), FlowPaths.parallelBranch(path, i), branchFlowSpec, sourceMap);
            }
        } else if (node instanceof Logical.Control) {
            Logical.Control ctrlNode = (Logical.Control) node;
            buildRecursive(ctrlNode.body(), FlowPaths.controlBody(path), spec, sourceMap);
        } else if (node instanceof Logical.Named) {
            Logical.Named namedNode = (Logical.Named) node;
            buildRecursive(namedNode.body(), path, spec, sourceMap);
        } else if (node instanceof Logical.Adapter) {
            Logical.Adapter adapterNode = (Logical.Adapter) node;
            buildRecursive(adapterNode.body(), path, spec, sourceMap);
        }
    }
}
