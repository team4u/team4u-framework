package com.team4u.criterion.compiler;

import com.team4u.criterion.MatchContext;
import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.model.Criterion;
import com.team4u.criterion.trace.TraceNode;
import com.team4u.criterion.trace.TraceRecorder;

/**
 * 支持追踪的匹配断言装饰器
 * 包装原有的 MatchPredicate，在上下文开启追踪时记录执行日志
 */
public class TracingMatchPredicate implements MatchPredicate {

    private final Criterion criterion;
    private final MatchPredicate delegate;

    public TracingMatchPredicate(Criterion criterion, MatchPredicate delegate) {
        this.criterion = criterion;
        this.delegate = delegate;
    }

    @Override
    public boolean test(MatchContext context) {
        // 1. context 为 null 时，直接委托给下游处理
        if (context == null) {
            return delegate.test(context);
        }

        TraceRecorder recorder = context.getRecorder();

        // 2. 如果没有开启追踪，直接走快速路径（零开销）
        if (recorder == null) {
            return delegate.test(context);
        }

        // 3. 开启追踪逻辑
        // 创建当前节点信息
        TraceNode node = new TraceNode(criterion, context.getActual());

        // 入栈
        recorder.begin(node);

        boolean result = false;
        try {
            // 执行实际逻辑 (可能会递归调用子节点的 TracingMatchPredicate)
            result = delegate.test(context);
            return result;
        } finally {
            // 出栈并回填结果
            recorder.end(result);
        }
    }
}
