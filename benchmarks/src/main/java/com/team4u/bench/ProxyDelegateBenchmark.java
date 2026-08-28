package com.team4u.bench;

import com.team4u.framework.proxy.ProxyBuilder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ProxyDelegateBenchmark {

    public interface ValueService {
        String value();
    }

    private static final class FixedValueService implements ValueService {
        @Override
        public String value() {
            return "value";
        }
    }

    private ValueService proxy;

    @Setup
    public void setUp() {
        proxy = ProxyBuilder.forClass(ValueService.class)
                .withDelegate(new FixedValueService())
                .build();
        if (!"value".equals(proxy.value())) {
            throw new IllegalStateException("JDK interface delegate proxy did not invoke its target");
        }
    }

    @Benchmark
    public String delegatedNoArgInvocation() {
        return proxy.value();
    }
}
