package com.team4u.framework.translator.engine;

import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.translator.api.ResponseTranslator;
import com.team4u.framework.translator.model.ErrorDef;
import com.team4u.framework.translator.model.RawResponse;
import com.team4u.framework.translator.model.TranslatedResponse;
import com.team4u.framework.translator.render.builtin.FallbackRenderPolicy;
import com.team4u.framework.translator.render.builtin.TemplateRenderPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 默认翻译门面与渲染引擎单元测试
 * <p>
 * 验证：上下分组匹配、兜底渲染逻辑、模板变量占位符渲染逻辑。
 */
public class DefaultResponseTranslatorTest {

    private RoutingManager routingManager;
    private ResponseTranslator translator;

    @Before
    public void setUp() {
        // 模拟路由管理器行为，以此解耦真实文件配置
        routingManager = Mockito.mock(RoutingManager.class);
        translator = new DefaultResponseTranslator(routingManager);
    }

    /**
     * 测试未命中路由策略时的兜底行为（原样返回）
     */
    @Test
    public void testTranslateUnmatched() {
        // 给定未匹配的结果
        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.unmatch());

        RawResponse request = RawResponse.of("ORDER", "O001", "订单不存在");
        Map<String, Object> args = new HashMap<>();

        // 当执行翻译
        TranslatedResponse response = translator.translate(request, "err_router", args);

        // 断言原样返回
        Assert.assertNotNull(response);
        Assert.assertEquals("O001", response.getCode());
        Assert.assertEquals("订单不存在", response.getMessage());
        Assert.assertNull(response.getTraceId());
    }

    /**
     * 测试匹配路由后，基础替换与兜底功能（未使用模板变量）
     */
    @Test
    public void testTranslateMatchedWithoutTemplate() {
        ErrorDef def = new ErrorDef();
        def.setCode("NEW_001");
        def.setDefaultMsg("订单已作废");

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        RawResponse request = RawResponse.of("ORDER", "O001", "订单不存在");

        TranslatedResponse response = translator.translate(request, "err_router", null);

        Assert.assertNotNull(response);
        Assert.assertEquals("NEW_001", response.getCode());
        Assert.assertEquals("订单已作废", response.getMessage());
        Assert.assertNull(response.getTraceId());
    }

    /**
     * 测试路由存在静态目标配置时，如果静态配置某些字段为空，由兜底渲染器补充的情况
     */
    @Test
    public void testTranslateFallbackFill() {
        ErrorDef def = new ErrorDef();
        def.setCode(""); // 没配置错误码
        def.setDefaultMsg("系统开小差了");

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        RawResponse request = RawResponse.of("PAY", "P_ERR_TIMEOUT", "支付超时");

        TranslatedResponse response = translator.translate(request, "err_router", null);

        Assert.assertNotNull(response);
        // 原始错误码被兜底填入
        Assert.assertEquals("P_ERR_TIMEOUT", response.getCode());
        // 映射的文案正常返回
        Assert.assertEquals("系统开小差了", response.getMessage());
    }

    /**
     * 测试变量模板渲染逻辑功能
     */
    @Test
    public void testTranslateWithTemplate() {
        ErrorDef def = new ErrorDef();
        def.setCode("SYSTEM_ERROR");
        // 配置了动态模板变量，包含内置参数 rawCode 和 rawMessage，也使用了业务参数
        def.setDefaultMsg("内部异常[${rawCode}]，原因：${rawMessage}。业务操作：${action}");

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        RawResponse request = RawResponse.of("SYSTEM", "NPE", "空指针异常");

        Map<String, Object> args = new HashMap<>();
        args.put("action", "查询明细");

        TranslatedResponse response = translator.translate(request, "err_router", args);

        Assert.assertNotNull(response);
        Assert.assertEquals("SYSTEM_ERROR", response.getCode());
        Assert.assertEquals("内部异常[NPE]，原因：空指针异常。业务操作：查询明细", response.getMessage());
    }

    /**
     * 测试输入参数为空时，期望快速失败抛出 NPE
     */
    @Test(expected = NullPointerException.class)
    public void testTranslateRejectsNullSource() {
        translator.translate(null, "err_router", null);
    }

    /**
     * 测试匹配路由的情况下，传递的 traceId 能被正确保留和回传
     */
    @Test
    public void testTranslateMatchedKeepsTraceId() {
        ErrorDef def = new ErrorDef();
        def.setCode("NEW_002");
        def.setDefaultMsg("订单异常");

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        Map<String, Object> args = new HashMap<>();
        args.put("traceId", "trace-001");

        TranslatedResponse response = translator.translate(
                RawResponse.of("ORDER", "O001", "订单不存在"),
                "err_router",
                args);

        Assert.assertEquals("trace-001", response.getTraceId());
    }

    /**
     * 测试未匹配到路由配置的情况下，传递的 traceId 依然能被正确保留和回传
     */
    @Test
    public void testTranslateUnmatchedKeepsTraceId() {
        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.unmatch());

        Map<String, Object> args = new HashMap<>();
        args.put("traceId", "trace-raw");

        TranslatedResponse response = translator.translate(
                RawResponse.of("ORDER", "O001", "订单不存在"),
                "err_router",
                args);

        Assert.assertEquals("trace-raw", response.getTraceId());
    }

    /**
     * 测试引擎对各类不符合规范的 traceId 进行归一化处理（空字符串处理为 null）
     */
    @Test
    public void testTranslateNormalizesTraceId() {
        ErrorDef def = new ErrorDef();
        def.setCode("NEW_003");
        def.setDefaultMsg("订单异常");

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        TranslatedResponse missing = translator.translate(
                RawResponse.of("ORDER", "O001", "订单不存在"),
                "err_router",
                null);
        Assert.assertNull(missing.getTraceId());

        Map<String, Object> emptyArgs = new HashMap<>();
        emptyArgs.put("traceId", "");
        TranslatedResponse empty = translator.translate(
                RawResponse.of("ORDER", "O001", "订单不存在"),
                "err_router",
                emptyArgs);
        Assert.assertNull(empty.getTraceId());

        Map<String, Object> literalArgs = new HashMap<>();
        literalArgs.put("traceId", "null");
        TranslatedResponse literal = translator.translate(
                RawResponse.of("ORDER", "O001", "订单不存在"),
                "err_router",
                literalArgs);
        Assert.assertEquals("null", literal.getTraceId());
    }

    /**
     * 测试渲染器的优先级问题：
     * <p>
     * 假设路由返回的 TargetDdf 的 message 为空，那么理论上：
     * 1. 之前 Template(先执行) 时，拿到的也是空，不会进行任何操作。
     * 2. 然后 Fallback(后执行) 会用请求源去填充。
     * 此时如果请求源自己带有了 ${xxx} 模板，也是不会被渲染的（这符合逻辑，因为兜底本质上是复原，源数据中的变量没有被渲染的必要，也不该作为模板被二次计算导致被污染风险）。
     * <p>
     * 测试此用例保证当前渲染顺序的情况下，fallback 补充回来的原文能正确原原本本地返回，不再发生预料之外的结果。
     */
    @Test
    public void testTranslateOrderFallbackOverTemplate() {
        ErrorDef def = new ErrorDef();
        def.setCode("FALLBACK_C");
        def.setDefaultMsg(null); // 为空，需要走 Fallback

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        // 故意让原始文本中带有一个看起来像模板的变量，它不应该被渲染引擎错误地处理
        RawResponse request = RawResponse.of("SYSTEM", "FALLBACK_O", "原始异常：${rawCode}");

        TranslatedResponse response = translator.translate(request, "err_router", null);

        Assert.assertNotNull(response);
        Assert.assertEquals("FALLBACK_C", response.getCode());
        Assert.assertEquals("原始异常：${rawCode}", response.getMessage());
    }

    /**
     * 测试当模板中存在未提供值的变量时，TextTemplate 能够按预期保留原样
     * 同时也验证了最近关于 Rule Engine 语法的变更（如支持 $ 前缀和无引号字符串等）是否会对现有的模板解析产生未预期的影响。
     */
    @Test
    public void testTranslateWithMissingTemplateVariable() {
        ErrorDef def = new ErrorDef();
        def.setCode("PARTIAL_RENDER");
        // 提供了一个存在，一个不存在的变量
        def.setDefaultMsg("已知：${rawMessage}，未知：${unknownVar}");

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        RawResponse request = RawResponse.of("SYS", "ERR", "系统异常");

        TranslatedResponse response = translator.translate(request, "err_router", null);

        Assert.assertNotNull(response);
        Assert.assertEquals("PARTIAL_RENDER", response.getCode());
        // 期望：rawMessage 被替换，unknownVar 保留原样
        Assert.assertEquals("已知：系统异常，未知：${unknownVar}", response.getMessage());
    }

    /**
     * 测试内置策略的执行优先级，确保 Template -> Fallback 的固定流转顺序
     */
    @Test
    public void testBuiltinPolicyPriorityOrder() {
        Assert.assertTrue(new TemplateRenderPolicy().priority() < new FallbackRenderPolicy().priority());
    }

    /**
     * 测试不同构造器初始化的行为差异：仅使用 SPI 注册，或是带有自定义包扫描能力
     */
    @Test
    public void testDefaultConstructorUsesSpiOnlyAndOptionalScanPackages() {
        ErrorDef def = new ErrorDef();
        def.setCode("SCAN");
        def.setDefaultMsg("base");

        Mockito.when(routingManager.route(eq("err_router"), any(), eq(ErrorDef.class)))
                .thenReturn(RouteResult.ruleMatch(def, (String) null));

        ResponseTranslator spiOnlyTranslator = new DefaultResponseTranslator(routingManager);
        TranslatedResponse spiOnly = spiOnlyTranslator.translate(
                RawResponse.of("ORDER", "O001", "订单不存在"),
                "err_router",
                null);
        Assert.assertEquals("base", spiOnly.getMessage());

        ResponseTranslator scanEnabledTranslator = new DefaultResponseTranslator(
                routingManager,
                "com.team4u.framework.translator.testpolicy");
        TranslatedResponse scanned = scanEnabledTranslator.translate(
                RawResponse.of("ORDER", "O001", "订单不存在"),
                "err_router",
                null);
        Assert.assertEquals("base|scanned", scanned.getMessage());
    }
}
