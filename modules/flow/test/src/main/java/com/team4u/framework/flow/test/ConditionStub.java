package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Condition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 守卫条件桩对象。
 *
 * @param <I> 输入类型
 * @author jay.wu
 */
public final class ConditionStub<I> implements Condition<I> {

    private final boolean result;
    private final Exception exceptionToThrow;
    private final List<I> recordedInputs = new ArrayList<>();

    private ConditionStub(boolean result, Exception exceptionToThrow) {
        this.result = result;
        this.exceptionToThrow = exceptionToThrow;
    }

    public static <I> ConditionStub<I> always(boolean result) {
        return new ConditionStub<>(result, null);
    }

    public static <I> ConditionStub<I> throwsException(Exception e) {
        return new ConditionStub<>(false, e);
    }

    @Override
    public synchronized boolean test(I input) throws Exception {
        recordedInputs.add(input);
        if (exceptionToThrow != null) {
            throw exceptionToThrow;
        }
        return result;
    }

    public synchronized int invocationCount() {
        return recordedInputs.size();
    }

    public synchronized List<I> recordedInputs() {
        return Collections.unmodifiableList(new ArrayList<>(recordedInputs));
    }
}
