package com.team4u.bench;

import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.model.RouteRule;
import com.team4u.framework.router.core.ExpressionRouter;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RouterRouteBenchmark {

    private ExpressionRouter router;
    private MatchContext context;

    @Setup
    public void setUp() {
        RoutePolicy policy = new RoutePolicy();
        policy.setRules(Collections.singletonList(new RouteRule("age >= 18", "adult")));
        router = new ExpressionRouter(policy);

        Map<String, Object> actual = new HashMap<String, Object>();
        actual.put("age", 20);
        context = MatchContext.of(actual);

        RouteResult<?> result = router.route(context);
        if (!result.isMatch() || !"adult".equals(result.getValue())) {
            throw new IllegalStateException("Prebuilt router did not select the expected rule");
        }
    }

    @Benchmark
    public RouteResult<?> routeDecision() {
        return router.route(context);
    }
}
