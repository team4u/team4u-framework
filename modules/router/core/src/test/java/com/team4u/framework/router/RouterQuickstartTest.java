package com.team4u.framework.router;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.router.api.builder.RoutePolicyBuilder;
import com.team4u.framework.router.api.interceptor.RouteInterceptor;
import com.team4u.framework.router.api.interceptor.RouteInvocation;
import com.team4u.framework.router.api.interceptor.RouteTraceObservation;
import com.team4u.framework.router.api.interceptor.TraceableRouteInterceptor;
import com.team4u.framework.router.api.model.RouteOutcome;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Router core quickstart covering the routing API without proxy or bean integration.
 */
public class RouterQuickstartTest {

    private static final String MAP_CONFIG =
            "{\"type\":\"map\",\"rules\":[{\"condition\":\"A\",\"value\":\"alpha\"}],"
                    + "\"fallbackValue\":\"default\"}";
    private static final String EXPRESSION_CONFIG =
            "{\"type\":\"expression\",\"rules\":[{\"condition\":\"vip == true\",\"value\":\"vip\"}],"
                    + "\"fallbackValue\":\"standard\"}";
    private static final String WEIGHT_CONFIG =
            "{\"type\":\"weight\",\"rules\":[{\"condition\":\"20\",\"value\":\"beta\"},"
                    + "{\"condition\":\"80\",\"value\":\"alpha\"}]}";

    private InMemoryConfigSource configSource;
    private ConfigManager configManager;
    private RoutingManager routingManager;

    @Before
    public void setUp() {
        RouterBootstrap.global().resetForTest();
        RoutingManager.resetGlobalForTest();

        configSource = new InMemoryConfigSource("router-core-quickstart", 0);
        configManager = ConfigManager.builder()
                .addSource(configSource)
                .addWatcher(configSource)
                .debounceWindow(0)
                .build();
        routingManager = RoutingManager.builder()
                .configManager(configManager)
                .build();
        RoutingManager.setGlobal(routingManager);

        configSource.putAndRefresh("router.quick.map", MAP_CONFIG);
        configSource.putAndRefresh("router.quick.expression", EXPRESSION_CONFIG);
        configSource.putAndRefresh("router.quick.weight", WEIGHT_CONFIG);
    }

    @After
    public void tearDown() {
        RoutingManager.resetGlobalForTest();
        RouterBootstrap.global().resetForTest();
    }

    @Test
    public void mapExpressionAndWeightRoutingUseThePublicApi() {
        RouteResult<String> mapResult = routingManager.route(
                "quick.map", "A", String.class);
        Assert.assertEquals("alpha", mapResult.getValue());
        Assert.assertEquals(RouteOutcome.RULE_MATCH, mapResult.getOutcome());

        RouteResult<String> fallbackResult = routingManager.route(
                "quick.map", "unknown", String.class);
        Assert.assertEquals("default", fallbackResult.getValue());
        Assert.assertEquals(RouteOutcome.FALLBACK_MATCH, fallbackResult.getOutcome());

        Map<String, Object> request = new HashMap<>();
        request.put("vip", true);
        Assert.assertEquals("vip", routingManager.route(
                "quick.expression", request, String.class).getValue());
        request.put("vip", false);
        Assert.assertEquals("standard", routingManager.route(
                "quick.expression", request, String.class).getValue());

        String firstKey = routingManager.route("quick.weight", "quick-user", String.class).getValue();
        String secondKey = routingManager.route("quick.weight", "quick-user", String.class).getValue();
        Assert.assertEquals(firstKey, secondKey);
        Assert.assertTrue("alpha".equals(firstKey) || "beta".equals(firstKey));
    }

    @Test
    public void policyBuildersProvideRepresentativePolicies() {
        RoutePolicy mapPolicy = RoutePolicyBuilder.<String>map()
                .id("builder-map")
                .rule("A", "alpha-builder")
                .fallback("default-builder")
                .build();
        Assert.assertEquals(
                "alpha-builder", routingManager.routeByPolicy(mapPolicy, "A").getValue());

        RoutePolicy expressionPolicy = RoutePolicyBuilder.<String>expression()
                .id("builder-expression")
                .rule("level >= 3", "advanced")
                .fallback("basic")
                .build();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("level", 3);
        Assert.assertEquals(
                "advanced", routingManager.routeByPolicy(expressionPolicy, user).getValue());

        RoutePolicy weightPolicy = RoutePolicyBuilder.<String>weight()
                .id("builder-weight")
                .rule("20", "beta-builder")
                .rule("80", "alpha-builder")
                .build();
        Object weightResult = routingManager.routeByPolicy(weightPolicy, "stable-user").getValue();
        Assert.assertTrue(
                "alpha-builder".equals(weightResult) || "beta-builder".equals(weightResult));
    }

    @Test
    public void traceExposesRuleAndInterceptorDiagnostics() {
        RouteInterceptor observer = new RecordingInterceptor();

        RoutingManager tracedManager = RoutingManager.builder()
                .configManager(configManager)
                .addInterceptor(observer)
                .build();

        RouteTrace<String> trace = tracedManager.traceByPolicy(
                RoutePolicyBuilder.<String>expression()
                        .id("traced-router")
                        .rule("vip == true", "vip")
                        .fallback("standard")
                        .build(),
                new HashMap<String, Object>());

        Assert.assertEquals("expression", trace.getRouterType());
        Assert.assertEquals("standard", trace.getResult().getValue());
        Assert.assertFalse(trace.getSteps().isEmpty());
        Assert.assertTrue(trace.getCostMs() >= 0);
        Assert.assertEquals(2, trace.getEvents().size());
        Assert.assertEquals("before", trace.getEvents().get(0).getPhase());
        Assert.assertEquals("after", trace.getEvents().get(1).getPhase());
    }

    @Test
    public void interceptorsCanModifyRequestAndShortCircuit() {
        RouteInterceptor modifier = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                invocation.setRequest("A");
                return invocation.proceed();
            }
        };
        RouteResult<String> modified = RoutingManager.builder()
                .configManager(configManager)
                .addInterceptor(modifier)
                .build()
                .routeByPolicy(RoutePolicyBuilder.<String>map()
                        .id("modified-map")
                        .rule("A", "alpha")
                        .build(), "ignored");

        Assert.assertEquals("alpha", modified.getValue());

        RouteResult<String> shortCircuited = RoutingManager.builder()
                .configManager(configManager)
                .addInterceptor(new ShortCircuitInterceptor())
                .build()
                .route("quick.map", "A", String.class);

        Assert.assertEquals("intercepted", shortCircuited.getValue());
        Assert.assertTrue(shortCircuited.isShortCircuited());
    }
    private static class ShortCircuitInterceptor implements RouteInterceptor {
        @Override
        public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
            return RouteResult.shortCircuited(asT("intercepted"), "quickstart");
        }

        @SuppressWarnings("unchecked")
        private static <T> T asT(String value) {
            return (T) value;
        }
    }

    private static class RecordingInterceptor implements TraceableRouteInterceptor {

        @Override
        public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
            return invocation.proceed();
        }

        @Override
        public <T> Object beforeTrace(RouteTraceObservation<T> observation) {
            return "before:" + observation.getRouterId();
        }

        @Override
        public <T> Object afterTrace(RouteTraceObservation<T> observation) {
            return "after:" + observation.getTrace().getResult().getValue();
        }
    }
}
