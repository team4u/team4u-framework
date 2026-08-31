package com.team4u.framework.flow;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.FlowObserver;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.spi.NodeDescriptor;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * FlowObserver 契约验证：八种运行时节点类型在 resume 前后均成对发出 STARTED/COMPLETED 事件，
 * 事件属性白名单不含敏感业务值，且观察者自身异常不得影响 flow 执行结果。
 */
public class ObserverContractTest {

    @Test
    public void eightNodeKindsEmitPairedLifecycleEventsAcrossResume() {
        ResumePoint<String> point = ResumePoint.named("observer-resume");
        Branch<String, String> accepted = Branch.of("accepted",
                (context, input) -> Outcome.accepted(input + "-parallel"));
        Flow<String, String> applicable = Flow.firstApplicable(
                Flow.skipped(Reason.of("NEXT", "try next")),
                Flow.parallel(accepted).join(results -> results.outcome(accepted)));
        Flow<String, String> routed = Flow.route(
                        (Operation<String, String>) (context, input) ->
                                Outcome.accepted("selected"))
                .caseOf("selected", applicable)
                .otherwise(Flow.accepted("otherwise"));
        Policy<String> policy = new Policy<String>() {
            @Override public Gate before(PolicyContext context, String key) {
                return Gate.proceed();
            }
        };
        Flow<String, String> controlled = routed
                .policy(policy, value -> value)
                .timeout(Duration.ofSeconds(2));
        Flow<String, String> flow = Flow.scope("observed", controlled)
                .await(point)
                .then((context, resumed) -> Outcome.accepted(
                        resumed.state() + ":" + resumed.signal()));

        final List<FlowObserver.Event> events = new ArrayList<FlowObserver.Event>();
        LocalExecutable<String, String> local = Local.compile(flow,
                OperationResolver.rejecting(), events::add);
        FlowResult.Suspended<String> suspended =
                (FlowResult.Suspended<String>) local.run("business-secret");
        assertEquals("business-secret-parallel:ok", local.resume(
                suspended.suspension(), point, "ok").requireAccepted());

        EnumSet<NodeDescriptor.Kind> startedKinds = EnumSet.noneOf(NodeDescriptor.Kind.class);
        EnumSet<NodeDescriptor.Kind> completedKinds = EnumSet.noneOf(NodeDescriptor.Kind.class);
        Map<String, Integer> starts = new HashMap<String, Integer>();
        Map<String, Integer> completions = new HashMap<String, Integer>();
        Set<String> allowedAttributes = new HashSet<String>(Arrays.asList(
                "outcome", "code", "durationNanos", "scope", "attempt",
                "branch", "branches", "wake", "resumePoint", "revision", "lifecycle"));
        for (FlowObserver.Event event : events) {
            assertTrue(allowedAttributes.containsAll(event.attributes().keySet()));
            assertFalse(event.attributes().containsValue("business-secret"));
            if (event.type() == FlowObserver.Type.NODE_STARTED) {
                startedKinds.add(event.descriptor().kind());
                starts.put(event.descriptor().path(), starts.getOrDefault(event.descriptor().path(), 0) + 1);
            } else if (event.type() == FlowObserver.Type.NODE_COMPLETED) {
                completedKinds.add(event.descriptor().kind());
                completions.put(event.descriptor().path(), completions.getOrDefault(event.descriptor().path(), 0) + 1);
                assertTrue(event.attributes().containsKey("outcome"));
            }
        }
        EnumSet<NodeDescriptor.Kind> runtimeKinds = EnumSet.allOf(NodeDescriptor.Kind.class);
        assertEquals(runtimeKinds, startedKinds);
        assertEquals(runtimeKinds, completedKinds);
        assertEquals(starts, completions);
        assertEquals(Integer.valueOf(1), starts.get("$/1"));
        int flowStartedCount = 0;
        for (FlowObserver.Event event : events) {
            if (event.type() == FlowObserver.Type.FLOW_STARTED) {
                flowStartedCount++;
            }
        }
        assertEquals(1, flowStartedCount);
    }

    @Test
    public void observerRuntimeExceptionsCannotChangeResult() {
        FlowObserver broken = event -> { throw new IllegalStateException("observer failed"); };
        assertEquals("value", Local.compile(Flow.<String>identity(),
                OperationResolver.rejecting(), broken).run("value").requireAccepted());
    }
}
