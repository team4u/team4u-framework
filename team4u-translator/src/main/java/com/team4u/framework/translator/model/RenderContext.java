package com.team4u.framework.translator.model;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 渲染管线流转上下文
 * <p>
 * 传递给所有 MessageRenderer 的执行上下文，保证非共享线程安全。
 */
@Getter
public class RenderContext {

    /**
     * 原始输入 (只读)
     */
    private final RawResponse source;

    /**
     * 路由静态配置 (只读)
     */
    private final ErrorDef routeDef;

    /**
     * 动态透传参数 (只读)
     */
    private final Map<String, Object> args;

    /**
     * 渲染过程中的最终码 (可变状态，允许渲染器覆盖)
     */
    @Setter
    private String finalCode;

    /**
     * 渲染过程中的最终文案 (可变状态，允许渲染器覆盖)
     */
    @Setter
    private String finalMessage;

    /**
     * 构造渲染上下文，并使用路由配置初始化最终码和最终文案。
     *
     * @param source   原始输入
     * @param routeDef 路由静态配置
     * @param args     动态透传参数
     */
    public RenderContext(RawResponse source, ErrorDef routeDef, Map<String, Object> args) {
        this.source = source;
        this.routeDef = routeDef;
        this.args = args;

        if (routeDef != null) {
            this.finalCode = routeDef.getCode();
            this.finalMessage = routeDef.getDefaultMsg();
        }
    }

    /**
     * 基于当前上下文中流转产生的 finalCode、finalMessage 和提取/注入的 traceId 生成最终的响应结果。
     *
     * @return 最终翻译结果（不可变对象）
     */
    public TranslatedResponse build(String traceId) {
        return new TranslatedResponse(this.finalCode, this.finalMessage, traceId);
    }
}
