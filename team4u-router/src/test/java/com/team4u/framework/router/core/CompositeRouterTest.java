package com.team4u.framework.router.core;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.RouterType;
import com.team4u.framework.router.api.builder.RoutePolicyBuilder;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.router.api.trace.RouteTrace;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * CompositeRouter 单元测试
 */
public class CompositeRouterTest {

    private TestConfigContext configContext;
    private RoutingManager routingManager;

    @Before
    public void setUp() {
        configContext = TestConfigContext.create();
        routingManager = RoutingManager.builder()
                .configManager(configContext.getManager())
                .build();
    }

    /**
     * 注册路由策略到配置上下文
     *
     * @param id     路由唯一标识
     * @param policy 路由策略对象
     */
    private void registerPolicy(String id, RoutePolicy policy) {
        policy.setId(id);
        configContext.put("router." + id, JSONUtil.toJsonStr(policy));
    }

    @Test
    public void testRouteShortCircuit() {
        // 使用 RoutePolicyBuilder 构建子路由配置，避免直接操作 JSON 字符串
        String childA = "childA";
        registerPolicy(childA, RoutePolicyBuilder.<String>map()
                .rule("A", "ValueA")
                .build());

        String childB = "childB";
        registerPolicy(childB, RoutePolicyBuilder.<String>map()
                .rule("B", "ValueB")
                .build());

        // 使用 RoutePolicyBuilder 构建组合路由，直接传 ID 字符串
        RoutePolicy compositePolicy = RoutePolicyBuilder.<String>composite()
                .delegates(childA, childB)
                .build();

        CompositeRouter compositeRouter = new CompositeRouter(compositePolicy, routingManager);

        // 1. 命中第一个子路由，应当直接返回子路由的结果（短路）
        RouteResult<String> resultA = compositeRouter.route("A");
        Assert.assertTrue(resultA.isMatch());
        Assert.assertEquals("ValueA", resultA.getValue());
        Assert.assertEquals("A", resultA.getMatchedCondition());

        // 2. 第一个未命中，命中第二个子路由
        RouteResult<String> resultB = compositeRouter.route("B");
        Assert.assertTrue(resultB.isMatch());
        Assert.assertEquals("ValueB", resultB.getValue());
        Assert.assertEquals("B", resultB.getMatchedCondition());

        // 3. 都不命中
        RouteResult<String> resultNone = compositeRouter.route("C");
        Assert.assertFalse(resultNone.isMatch());
    }

    @Test
    public void testRouteFallbackAccumulation() {
        // 配置两个带兜底的子路由
        String childA = "childA";
        registerPolicy(childA, RoutePolicyBuilder.<String>map()
                .fallback("FallbackA")
                .build());

        String childB = "childB";
        registerPolicy(childB, RoutePolicyBuilder.<String>map()
                .fallback("FallbackB")
                .build());

        // 配置组合路由
        RoutePolicy compositePolicy = RoutePolicyBuilder.<String>composite()
                .delegates(childA, childB)
                .fallback("CompositeFallback")
                .build();

        CompositeRouter compositeRouter = new CompositeRouter(compositePolicy, routingManager);

        // 场景：子路由都没有真实命中规则（condition=null），但有 fallback 产生
        // 根据逻辑，后执行的子路由的 fallback 会覆盖前面的。
        // 子路由 B 的 fallback 会覆盖 A 的。
        RouteResult<String> result = compositeRouter.route("any");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("FallbackB", result.getValue());
        Assert.assertNull(result.getMatchedConditions());
    }

    @Test
    public void testRouteCompositeFallback() {
        // 配置一个不命中的子路由（且无兜底）
        String childA = "childA";
        registerPolicy(childA, RoutePolicyBuilder.<String>map()
                .rule("A", "ValueA")
                .build());

        // 配置组合路由，自带兜底
        RoutePolicy compositePolicy = RoutePolicyBuilder.<String>composite()
                .delegates(childA)
                .fallback("CompositeFallback")
                .build();

        CompositeRouter compositeRouter = new CompositeRouter(compositePolicy, routingManager);

        // 子路由不命中，且无子路由产生 fallback，则使用组合路由自身的 fallback
        RouteResult<String> result = compositeRouter.route("unknown");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("CompositeFallback", result.getValue());
    }

    @Test
    public void testTrace() {
        // 配置子路由
        String childA = "childA";
        registerPolicy(childA, RoutePolicyBuilder.<String>map()
                .rule("A", "ValueA")
                .build());

        String childB = "childB";
        registerPolicy(childB, RoutePolicyBuilder.<String>map()
                .rule("B", "ValueB")
                .build());

        // 配置组合路由
        RoutePolicy compositePolicy = RoutePolicyBuilder.<String>composite()
                .delegates(childA, childB)
                .build();

        CompositeRouter compositeRouter = new CompositeRouter(compositePolicy, routingManager);

        // 追踪命中第二条路径的情况
        RouteTrace<String> trace = compositeRouter.trace("B");

        Assert.assertTrue(trace.getResult().isMatch());
        Assert.assertEquals("ValueB", trace.getResult().getValue());
        Assert.assertEquals(RouterType.COMPOSITE, trace.getRouterType());

        // 应该有两个步骤（childA 不匹配，childB 匹配）
        Assert.assertEquals(2, trace.getSteps().size());

        // 第一个步骤：childA 没匹配
        Assert.assertEquals("childA", trace.getSteps().get(0).getCondition());
        Assert.assertFalse(trace.getSteps().get(0).isMatched());

        // 第二个步骤：childB 匹配
        Assert.assertEquals("childB", trace.getSteps().get(1).getCondition());
        Assert.assertTrue(trace.getSteps().get(1).isMatched());
    }

    @Test
    public void testNoDelegates() {
        // 使用 Builder 构建无委派列表的策略
        RoutePolicy compositePolicy = RoutePolicyBuilder.<String>composite()
                .fallback("Fallback")
                .build();

        CompositeRouter compositeRouter = new CompositeRouter(compositePolicy, routingManager);

        RouteResult<String> result = compositeRouter.route("any");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("Fallback", result.getValue());
    }

    @Test
    public void testNestedCompositeRouter() {
        // 1. 叶子节点路由 (Map)
        String leafId = "leafId";
        registerPolicy(leafId, RoutePolicyBuilder.<String>map()
                .rule("target", "LeafMatched")
                .fallback("LeafFallback")
                .build());

        // 2. 中间层组合路由 (Composite)
        String middleId = "middleComposite";
        registerPolicy(middleId, RoutePolicyBuilder.<String>composite()
                .delegates(leafId)
                .build());

        // 3. 根组合路由 (Composite)
        RoutePolicy rootPolicy = RoutePolicyBuilder.<String>composite()
                .delegates(middleId)
                .build();

        CompositeRouter rootRouter = new CompositeRouter(rootPolicy, routingManager);

        // --- 路由测试 ---
        // 正常命中叶子节点
        RouteResult<String> result = rootRouter.route("target");
        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("LeafMatched", result.getValue());

        // 命中叶子节点兜底
        RouteResult<String> resultFallback = rootRouter.route("unknown");
        Assert.assertTrue(resultFallback.isMatch());
        Assert.assertEquals("LeafFallback", resultFallback.getValue());

        // --- 追踪测试 ---
        RouteTrace<String> trace = rootRouter.trace("target");
        Assert.assertTrue(trace.getResult().isMatch());
        Assert.assertEquals("LeafMatched", trace.getResult().getValue());

        // 验证追踪层级: Root -> Middle
        Assert.assertEquals(1, trace.getSteps().size());
        Assert.assertEquals(middleId, trace.getSteps().get(0).getCondition());

        // 取出中间层的子 Trace 进行进一步验证
        @SuppressWarnings("unchecked")
        RouteTrace<String> middleTrace = (RouteTrace<String>) trace.getSteps().get(0).getDiagnosticDetail();
        Assert.assertEquals(1, middleTrace.getSteps().size());
        Assert.assertEquals(leafId, middleTrace.getSteps().get(0).getCondition());
    }
}
