package com.team4u.bench;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.MatchPredicate;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CriterionMatchBenchmark {

    private MatchPredicate compiledPredicate;
    private MatchContext context;
    private MatchPredicate numericPredicate;
    private MatchContext numericContext;

    @Setup
    public void setUp() {
        Map<String, Object> actual = new HashMap<String, Object>();
        actual.put("age", 20);
        actual.put("status", "ACTIVE");
        context = MatchContext.of(actual);

        Criteria criteria = Criteria.builder().build();
        compiledPredicate = criteria.compileExpression("age >= 18 && status == 'ACTIVE'");
        numericPredicate = criteria.compileExpression("it > 18");
        numericContext = MatchContext.of(20);

        if (!compiledPredicate.test(context)) {
            throw new IllegalStateException("Compiled logical predicate did not match its context");
        }
        if (!numericPredicate.test(numericContext)) {
            throw new IllegalStateException("Compiled numeric predicate did not match its context");
        }
    }

    @Benchmark
    public boolean compiledPredicate() {
        return compiledPredicate.test(context);
    }

    @Benchmark
    public boolean numericComparison() {
        return numericPredicate.test(numericContext);
    }
}
