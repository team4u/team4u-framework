package com.team4u.framework.flow.durable;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

/**
 * 测试共享工具：可观测的 Operation、可控崩溃、自定义 StateMapper 与记录型 Store。
 */
final class DurableTestOps {
    private DurableTestOps() {
    }

    /** 记录每次调用（invocationId 与输入）并可按次数抛异常模拟崩溃。 */
    static final class RecordingOp implements Operation<String, String> {
        @lombok.Getter
        @lombok.experimental.Accessors(fluent = true)
        private final String tag;
        private final AtomicInteger calls = new AtomicInteger();
        @lombok.Getter
        @lombok.experimental.Accessors(fluent = true)
        private final List<String> invocations = new ArrayList<String>();
        @lombok.Getter
        @lombok.experimental.Accessors(fluent = true)
        private final List<String> inputs = new ArrayList<String>();
        private int crashBeforeComplete = -1;
        private Outcome<String> fixed = null;

        RecordingOp(String tag) {
            this.tag = tag;
        }

        RecordingOp crashOnCall(int n) {
            this.crashBeforeComplete = n;
            return this;
        }

        RecordingOp returns(Outcome<String> outcome) {
            this.fixed = outcome;
            return this;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            int call = calls.incrementAndGet();
            invocations.add(context.invocationId());
            inputs.add(input);
            if (call == crashBeforeComplete) {
                throw new SimulatedCrash("crash at call " + call + " of " + tag);
            }
            if (fixed != null) {
                return fixed;
            }
            return Outcome.accepted(input + ">" + tag);
        }
    }

    /**
     * 模拟进程崩溃：以 Error 语义穿透框架（Operation 异常会被转为
     * OPERATION_EXCEPTION Failed，而 Error 等价于进程死亡——不提交任何检查点）。
     */
    @SuppressWarnings("serial")
    static class SimulatedCrash extends Error {
        SimulatedCrash(String message) {
            super(message);
        }
    }

    /** 透明计数版本：编码两次（读+写）计数的 mapper。 */
    static final class CountingMapper implements StateMapper {
        final StateMapper delegate;
        final AtomicInteger encodes = new AtomicInteger();
        final AtomicInteger decodes = new AtomicInteger();

        CountingMapper(StateMapper delegate) {
            this.delegate = delegate;
        }

        @Override
        public StoredValue encode(Object value) throws Exception {
            encodes.incrementAndGet();
            return delegate.encode(value);
        }

        @Override
        public Object decode(StoredValue storedValue) throws Exception {
            decodes.incrementAndGet();
            return delegate.decode(storedValue);
        }
    }

    /** 总是失败的 mapper，用于触发 CODEC_FAILURE。 */
    static final class FailingMapper implements StateMapper {
        @Override
        public StoredValue encode(Object value) throws Exception {
            throw new IllegalStateException("encode refused");
        }

        @Override
        public Object decode(StoredValue storedValue) throws Exception {
            throw new IllegalStateException("decode refused");
        }
    }

    /** 可注入异常并记录 CAS 调用的 store 包装。 */
    static class FaultyStore implements DurableStore {
        interface Guard {
            void apply(String executionId, long expectedRevision, DurableSnapshot update);
        }

        final DurableStore delegate;
        final List<String> loads = new ArrayList<String>();
        final List<Long> revisions = new ArrayList<Long>();
        volatile Guard onCas;
        volatile boolean failLoad;

        FaultyStore(DurableStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<DurableSnapshot> load(String executionId) {
            loads.add(executionId);
            if (failLoad) {
                throw new IllegalStateException("store load failed");
            }
            return delegate.load(executionId);
        }

        @Override
        public boolean compareAndSet(String executionId, long expectedRevision,
                                     DurableSnapshot update) {
            revisions.add(expectedRevision);
            Guard guard = onCas;
            if (guard != null) {
                guard.apply(executionId, expectedRevision, update);
            }
            return delegate.compareAndSet(executionId, expectedRevision, update);
        }
    }

    /** 收集事件的 DurableObserver。 */
    static final class RecordingDurableObserver implements DurableObserver {
        final List<Event> events = new ArrayList<Event>();

        @Override
        public void onEvent(Event event) {
            events.add(event);
        }

        List<Event> byType(Type type) {
            List<Event> found = new ArrayList<Event>();
            for (Event event : events) {
                if (event.type() == type) {
                    found.add(event);
                }
            }
            return found;
        }
    }

    static Outcome<String> failed(String code) {
        return Outcome.failed(Failure.of(code, code.toLowerCase()));
    }

    static Outcome<String> rejected(String code) {
        return Outcome.rejected(Reason.of(code, code.toLowerCase()));
    }

    static Outcome<String> skipped(String code) {
        return Outcome.skipped(Reason.of(code, code.toLowerCase()));
    }

    static String acceptedValue(DurableResult<String> result) {
        DurableResult.Completed<String> completed = (DurableResult.Completed<String>) result;
        return ((Outcome.Accepted<String>) completed.outcome()).value();
    }

    @SuppressWarnings("unchecked")
    static <T> T acceptedValueOf(DurableResult<T> result) {
        DurableResult.Completed<T> completed = (DurableResult.Completed<T>) result;
        return (T) ((Outcome.Accepted<T>) completed.outcome()).value();
    }

    static Map<String, Integer> invocationCounts(RecordingOp... ops) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (RecordingOp op : ops) {
            counts.put(op.tag(), op.calls());
        }
        return counts;
    }
}
