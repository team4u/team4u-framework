package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Completion;
import com.team4u.framework.flow.Gate;
import com.team4u.framework.flow.Policy;
import com.team4u.framework.flow.PolicyContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 线程安全的可编程控制策略测试桩（Programmable Policy Test Stub）。
 *
 * <p>实现 {@link Policy} 接口，支持配置 {@link Gate} 决策（Proceed/Reject/Fail），
 * 并以线程安全列表记录前置拦截 {@link BeforeCall} 与后置回调 {@link AfterCall}。</p>
 *
 * @param <K> 策略键类型
 * @author jay.wu
 */
public final class PolicyStub<K> implements Policy<K> {


    /** before 调用记录：重试 attempt 与策略键。 */
    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    @lombok.AllArgsConstructor
    @lombok.ToString
    public static final class BeforeCall<K> {
        private final int attempt;
        private final K key;
    }

    /** after 调用记录：重试 attempt 与完成摘要。 */
    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    @lombok.AllArgsConstructor
    @lombok.ToString
    public static final class AfterCall<K> {
        private final int attempt;
        private final K key;
        private final Completion completion;
    }

    private final CopyOnWriteArrayList<BeforeCall<K>> beforeCalls =
            new CopyOnWriteArrayList<BeforeCall<K>>();
    private final CopyOnWriteArrayList<AfterCall<K>> afterCalls =
            new CopyOnWriteArrayList<AfterCall<K>>();
    private volatile Gate gate = Gate.proceed();

    /** 以固定放行决策构造桩。 */
    public static <K> PolicyStub<K> proceeding() {
        return new PolicyStub<K>();
    }

    /** 以指定 Gate 决策构造桩（Reject/Fail 会直接终出对应 Outcome）。 */
    public static <K> PolicyStub<K> deciding(Gate gate) {
        PolicyStub<K> stub = new PolicyStub<K>();
        stub.alwaysDecide(gate);
        return stub;
    }

    /** 更新 before 的固定决策。 */
    public void alwaysDecide(Gate gate) {
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
    }

    @Override
    public Gate before(PolicyContext context, K key) {
        Objects.requireNonNull(context, "context must not be null");
        beforeCalls.add(new BeforeCall<K>(context.attempt(), key));
        return Objects.requireNonNull(gate, "gate must not be null");
    }

    @Override
    public void after(PolicyContext context, K key, Completion completion) {
        Objects.requireNonNull(context, "context must not be null");
        afterCalls.add(new AfterCall<K>(context.attempt(), key, completion));
    }

    public List<BeforeCall<K>> beforeCalls() {
        return Collections.unmodifiableList(new ArrayList<BeforeCall<K>>(beforeCalls));
    }

    public List<AfterCall<K>> afterCalls() {
        return Collections.unmodifiableList(new ArrayList<AfterCall<K>>(afterCalls));
    }

    public int beforeCount() {
        return beforeCalls.size();
    }

    public int afterCount() {
        return afterCalls.size();
    }

    public void reset() {
        beforeCalls.clear();
        afterCalls.clear();
    }
}
