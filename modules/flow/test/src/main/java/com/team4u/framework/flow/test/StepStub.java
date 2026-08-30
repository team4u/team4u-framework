package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 步骤桩对象：用于测试中记录调用次数、入参并定制返回值或异常。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author jay.wu
 */
public final class StepStub<I, O> implements Step<I, O> {

    private final Function<I, O> function;
    private final Exception exceptionToThrow;
    private final List<I> recordedInputs = new ArrayList<>();

    private StepStub(Function<I, O> function, Exception exceptionToThrow) {
        this.function = function;
        this.exceptionToThrow = exceptionToThrow;
    }

    public static <I, O> StepStub<I, O> returns(O value) {
        return new StepStub<>(in -> value, null);
    }

    public static <I, O> StepStub<I, O> function(Function<I, O> fn) {
        return new StepStub<>(fn, null);
    }

    public static <I, O> StepStub<I, O> throwsException(Exception e) {
        return new StepStub<>(null, e);
    }

    @Override
    public synchronized O apply(I input) throws Exception {
        recordedInputs.add(input);
        if (exceptionToThrow != null) {
            throw exceptionToThrow;
        }
        return function.apply(input);
    }

    public synchronized int invocationCount() {
        return recordedInputs.size();
    }

    public synchronized List<I> recordedInputs() {
        return Collections.unmodifiableList(new ArrayList<>(recordedInputs));
    }

    public synchronized I lastInput() {
        if (recordedInputs.isEmpty()) {
            return null;
        }
        return recordedInputs.get(recordedInputs.size() - 1);
    }
}
