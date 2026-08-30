package com.team4u.framework.translator.api;

import com.team4u.framework.translator.model.RawResponse;
import com.team4u.framework.translator.model.TranslatedResponse;

import java.util.Map;

/**
 * 响应翻译器核心门面接口
 * <p>
 * 定义将内部系统返回的原始响应，按路由规则翻译成对外暴露统一契约的规范操作。
 */
public interface ResponseTranslator {

    /**
     * 根据路由配置及变量参数翻译原始响应对象
     *
     * @param source   原始输入对象，不能为空
     * @param routerId 路由策略标识
     * @param args     动态透传参数，可为空
     * @return 翻译后的 Immutable 响应结果
     * @throws NullPointerException 当 source 为 null 时抛出
     */
    TranslatedResponse translate(RawResponse source, String routerId, Map<String, Object> args);
}
