package com.team4u.framework.flow.test;

import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 线程安全的可编程业务操作测试桩（Programmable Operation Test Stub）。
 *
 * <p>实现 {@link Operation} 接口，支持配置固定返回四态结果（{@code accepting} / {@code rejecting} / {@code skipping} / {@code failing}）
 * 或抛出异常（{@code throwing}），并以线程安全的方式记录每次调用的入参、{@code invocationId} 与 {@code attempt}。</p>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author jay.wu
 */
public final class OperationStub<I, O> implements Operation<I, O> {


    /** 自定义应答函数：按上下文与入参产生 Outcome。 */
    @FunctionalInterface
    public interface Answer<I, O> {
        Outcome<O> answer(OperationContext context, I input) throws Exception;
    }

    /** 单次调用记录：入参、幂等 invocationId 与重试 attempt。 */
    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    @lombok.AllArgsConstructor
    @lombok.ToString
    public static final class Call<I> {
        private final I input;
        private final String invocationId;
        private final int attempt;
    }

    private final Answer<I, O> answer;
    private final CopyOnWriteArrayList<Call<I>> calls = new CopyOnWriteArrayList<Call<I>>();

    private OperationStub(Answer<I, O> answer) {
        this.answer = Objects.requireNonNull(answer, "answer must not be null");
    }

    /** 以自定义应答函数构造桩。 */
    public static <I, O> OperationStub<I, O> answering(final Answer<I, O> answer) {
        return new OperationStub<I, O>(answer);
    }

    /** 每次调用对入参应用函数并返回 Accepted（函数返回值不可为 null）。 */
    public static <I, O> OperationStub<I, O> accepting(final Function<I, O> value) {
        Objects.requireNonNull(value, "value function must not be null");
        return answering(new Answer<I, O>() {
            @Override
            public Outcome<O> answer(OperationContext context, I input) {
                return Outcome.accepted(value.apply(input));
            }
        });
    }

    /** 固定返回 Rejected。 */
    public static <I, O> OperationStub<I, O> rejecting(Reason reason) {
        final Reason fixed = Objects.requireNonNull(reason, "reason must not be null");
        return answering(new Answer<I, O>() {
            @Override
            public Outcome<O> answer(OperationContext context, I input) {
                return Outcome.rejected(fixed);
            }
        });
    }

    /** 固定返回 Skipped。 */
    public static <I, O> OperationStub<I, O> skipping(Reason reason) {
        final Reason fixed = Objects.requireNonNull(reason, "reason must not be null");
        return answering(new Answer<I, O>() {
            @Override
            public Outcome<O> answer(OperationContext context, I input) {
                return Outcome.skipped(fixed);
            }
        });
    }

    /** 固定返回 Failed。 */
    public static <I, O> OperationStub<I, O> failing(Failure failure) {
        final Failure fixed = Objects.requireNonNull(failure, "failure must not be null");
        return answering(new Answer<I, O>() {
            @Override
            public Outcome<O> answer(OperationContext context, I input) {
                return Outcome.failed(fixed);
            }
        });
    }

    /** 每次调用抛出 supplier 新建的异常（框架会将其转换为 Failed Outcome）。 */
    public static <I, O> OperationStub<I, O> throwing(final Supplier<Exception> supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        return answering(new Answer<I, O>() {
            @Override
            public Outcome<O> answer(OperationContext context, I input) throws Exception {
                throw Objects.requireNonNull(supplier.get(), "exception supplier returned null");
            }
        });
    }

    @Override
    public Outcome<O> execute(OperationContext context, I input) throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        calls.add(new Call<I>(input, context.invocationId(), 0));
        return Objects.requireNonNull(answer.answer(context, input), "stub answer must not be null");
    }

    /** 返回按到达顺序的调用记录快照（不可变）。 */
    public List<Call<I>> calls() {
        return Collections.unmodifiableList(new ArrayList<Call<I>>(calls));
    }

    public int callCount() {
        return calls.size();
    }

    /** 最近一次调用的入参，无调用时返回 null。 */
    public I lastInput() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1).input();
    }

    /** 清空调用记录（不影响应答行为）。 */
    public void reset() {
        calls.clear();
    }
}
