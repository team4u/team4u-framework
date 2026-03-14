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

import java.util.*;

/**
 * 核心流转引擎实现类
 * <p>
 * 基于三段式流水线架构（上下文组装 -> 极速路由决策 -> 责任链渲染）。
 */
public class DefaultResponseTranslator implements ResponseTranslator {

    private final RoutingManager routingManager;
    private final OrderedPolicyChain<RenderContext, RenderPolicy> renderPipeline;

    /**
     * 构建默认实例（使用全局路由，仅通过 SPI 注册策略）
     */
    public DefaultResponseTranslator() {
        this(RoutingManager.global());
    }

    /**
     * 构建包含指定包扫描路径的实例
     *
     * @param scanPackages 需被扫描策略的包路径列表
     */
    public DefaultResponseTranslator(String... scanPackages) {
        this(RoutingManager.global(), scanPackages);
    }

    /**
     * 构建具有指定路由管理器的实例（仅通过 SPI 注册策略）
     *
     * @param routingManager 路由管理器
     */
    public DefaultResponseTranslator(RoutingManager routingManager) {
        this(routingManager, (String[]) null);
    }

    /**
     * 完全自定义构建，包含指定的路由管理器和需要扫描的包
     *
     * @param routingManager 路由管理器
     * @param scanPackages   需被扫描策略的包路径列表
     */
    public DefaultResponseTranslator(RoutingManager routingManager, String... scanPackages) {
        this.routingManager = Objects.requireNonNull(routingManager, "routingManager must not be null");

        this.renderPipeline = new OrderedPolicyChain<>(RenderPolicy.class);

        PolicyScanner.registerFromServiceLoader(this.renderPipeline);
        registerScanPackages(scanPackages);
    }

    @Override
    public TranslatedResponse translate(RawResponse source, String routerId, Map<String, Object> args) {
        // 1. 基础校验与参数快照
        Objects.requireNonNull(source, "source must not be null");
        Map<String, Object> safeArgs = snapshotArgs(args);
        String traceId = extractTraceId(safeArgs);

        // 2. 构建匹配上下文并执行路由决策
        MatchContext matchCtx = MatchContext.of(source).setAttributes(safeArgs);
        RouteResult<ErrorDef> result = routingManager.route(routerId, matchCtx, ErrorDef.class);

        // 3. 处理未命中路由的场景，保留原始响应与 traceId
        if (result == null || !result.isMatch()) {
            return new TranslatedResponse(source.getCode(), source.getMessage(), traceId);
        }

        // 4. 初始化渲染管线上下文并驱动责任链策略执行
        RenderContext renderCtx = new RenderContext(source, result.getValue(), safeArgs);
        for (RenderPolicy renderer : renderPipeline.allMatches(renderCtx)) {
            renderer.render(renderCtx);
        }

        // 5. 构建并返回最终不可变结果
        return renderCtx.build(traceId);
    }

    /**
     * 注册并加载指定包路径下的渲染策略
     *
     * @param scanPackages 包路径列表
     */
    private void registerScanPackages(String... scanPackages) {
        if (scanPackages == null || scanPackages.length == 0) {
            return;
        }

        Set<String> normalizedPackages = new LinkedHashSet<>();
        for (String scanPackage : scanPackages) {
            if (scanPackage == null) {
                continue;
            }
            String trimmed = scanPackage.trim();
            if (!trimmed.isEmpty()) {
                normalizedPackages.add(trimmed);
            }
        }

        for (String scanPackage : normalizedPackages) {
            PolicyScanner.scanAndRegister(this.renderPipeline, scanPackage);
        }
    }

    /**
     * 为传入的动态参数生成不可变安全快照
     *
     * @param args 原始参数
     * @return 独立、不可变的参数集合
     */
    private Map<String, Object> snapshotArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<>(args));
    }

    /**
     * 从安全参数集合中提取链路追踪标识
     *
     * @param args 安全参数集合
     * @return 链路追踪标识或 null
     */
    private String extractTraceId(Map<String, Object> args) {
        Object value = args.get("traceId");
        if (value == null) {
            return null;
        }
        String traceId = String.valueOf(value);
        return traceId.isEmpty() ? null : traceId;
    }
}
