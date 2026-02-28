package com.team4u.framework.translator.engine;

import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.util.PolicyScanner;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.model.RouteResult;
import com.team4u.framework.translator.api.ResponseTranslator;
import com.team4u.framework.translator.model.ErrorDef;
import com.team4u.framework.translator.model.RawResponse;
import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.model.TranslatedResponse;
import com.team4u.framework.translator.render.RenderPolicy;

import java.util.Map;

/**
 * 核心流转引擎实现类
 * <p>
 * 基于三段式流水线架构（上下文组装 -> 极速路由决策 -> 责任链渲染）。
 */
public class DefaultResponseTranslator implements ResponseTranslator {

    private final RoutingManager routingManager;
    private final OrderedPolicyChain<RenderContext, RenderPolicy> renderPipeline;

    public DefaultResponseTranslator() {
        this(RoutingManager.global());
    }

    public DefaultResponseTranslator(RoutingManager routingManager) {
        this.routingManager = routingManager;

        // 初始化 OrderedPolicyChain
        this.renderPipeline = new OrderedPolicyChain<>(RenderPolicy.class);

        // 自动扫描并注册渲染器，支持 SPI 和包扫描
        PolicyScanner.registerFromServiceLoader(this.renderPipeline);
        PolicyScanner.scanAndRegister(this.renderPipeline);
    }

    @Override
    public TranslatedResponse translate(RawResponse source, String routerId, Map<String, Object> args) {
        if (source == null) {
            return null;
        }

        // 1. 构建路由上下文
        MatchContext matchCtx = MatchContext.of(source).setAttributes(args);

        // 2. 调用路由引擎
        RouteResult<ErrorDef> result = routingManager.route(routerId, matchCtx, ErrorDef.class);

        // 3. 未命中处理
        if (result == null || !result.isMatch()) {
            return new TranslatedResponse(source.getCode(), source.getMessage(), null);
        }

        // 4. 初始化渲染管线上下文
        RenderContext renderCtx = new RenderContext(source, result.getValue(), args);

        // 5. 驱动管线
        for (RenderPolicy renderer : renderPipeline.allMatches(renderCtx)) {
            renderer.render(renderCtx);
        }

        // 6. 输出结果
        String traceId = args != null ? String.valueOf(args.getOrDefault("traceId", "")) : null;
        if (traceId == null || "null".equals(traceId) || traceId.isEmpty()) {
            traceId = null;
        }
        return renderCtx.build(traceId);
    }
}
