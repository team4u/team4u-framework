package com.team4u.framework.translator.testpolicy;

import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.render.RenderPolicy;

/**
 * 用于测试包扫描注册机制的渲染策略
 * 此类不会通过 SPI 注册，仅作为显式配置特定包路径时的命中验证对象
 */
public class ScanOnlyTestRenderPolicy implements RenderPolicy {

    @Override
    public int priority() {
        return NORMAL + 10; // 保障此策略晚于某些内置策略执行
    }

    @Override
    public boolean supports(RenderContext context) {
        return true; // 总是满足测试支持条件
    }

    @Override
    public void render(RenderContext context) {
        context.setFinalMessage(context.getFinalMessage() + "|scanned");
    }
}
