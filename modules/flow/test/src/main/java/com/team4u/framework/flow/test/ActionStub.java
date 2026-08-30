package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 动作桩对象：用于测试中记录调用次数、入参并定制异常。
 *
 * @param <I> 输入类型
 * @author jay.wu
 */
public final class ActionStub<I> implements Action<I> {

    private final Exception exceptionToThrow;
    private final List<I> recordedInputs = new ArrayList<>();

    private ActionStub(Exception exceptionToThrow) {
        this.exceptionToThrow = exceptionToThrow;
    }

    public static <I> ActionStub<I> create() {
        return new ActionStub<>(null);
    }

    public static <I> ActionStub<I> throwsException(Exception e) {
        return new ActionStub<>(e);
    }

    @Override
    public synchronized void execute(I input) throws Exception {
        recordedInputs.add(input);
        if (exceptionToThrow != null) {
            throw exceptionToThrow;
        }
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
