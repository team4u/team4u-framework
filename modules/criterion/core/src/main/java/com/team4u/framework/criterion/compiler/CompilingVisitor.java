package com.team4u.framework.criterion.compiler;

import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.CriterionVisitor;

/**
 * 具备自动降级能力的编译访问者
 * 编译结果会自动包裹在 TracingMatchPredicate 中，支持运行时追踪
 */
public class CompilingVisitor implements CriterionVisitor<MatchPredicate> {
    private final CompilerRegistry compilerRegistry;

    public CompilingVisitor(CompilerRegistry compilerRegistry) {
        this.compilerRegistry = compilerRegistry;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public MatchPredicate visit(Criterion criterion) {
        if (criterion == null) {
            return MatchPredicate.FALSE;
        }
        // 尝试查找专用编译器 (现在包括 Evaluator)
        CriterionCompiler compiler = compilerRegistry.get(criterion.getClass()).orElse(null);

        MatchPredicate predicate;
        if (compiler != null) {
            predicate = compiler.compile(criterion, this);
        } else {
            // 默认降级
            predicate = MatchPredicate.FALSE;
        }

        // 将编译结果包裹在追踪装饰器中
        // 这样生成的函数既可以高性能执行，也可以在 Context 携带 Recorder 时进行追踪
        return new TracingMatchPredicate(criterion, predicate);
    }
}
