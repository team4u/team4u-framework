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
 * 记录 before/after 调用并可配置 Gate 决策的线程安全 Policy 桩。
 *
 * <p>before 每次调用记录 {@link BeforeCall}（attempt 与策略键），after 每次调用记录
 * {@link AfterCall}（attempt 与完成摘要）。默认决策为 {@link Gate#proceed()}。</p>
 */
public final class PolicyStub<K> implements Policy<K> {

    /** before 调用记录：重试 attempt 与策略键。 */
    public static final class BeforeCall<K> {
        private final int attempt;
        private final K key;

        BeforeCall(int attempt, K key) {
            this.attempt = attempt;
            this.key = key;
        }

        public int attempt() {
            return attempt;
        }

        public K key() {
            return key;
        }

        @Override
        public String toString() {
            return "BeforeCall[attempt=" + attempt + ", key=" + key + "]";
        }
    }

    /** after 调用记录：重试 attempt 与完成摘要。 */
    public static final class AfterCall<K> {
        private final int attempt;
        private final K key;
        private final Completion completion;

        AfterCall(int attempt, K key, Completion completion) {
            this.attempt = attempt;
            this.key = key;
            this.completion = completion;
        }

        public int attempt() {
            return attempt;
        }

        public K key() {
            return key;
        }

        public Completion completion() {
            return completion;
        }

        @Override
        public String toString() {
            return "AfterCall[attempt=" + attempt + ", key=" + key
                    + ", completion=" + completion + "]";
        }
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
