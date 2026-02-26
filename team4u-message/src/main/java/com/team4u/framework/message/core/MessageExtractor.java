package com.team4u.framework.message.core;

import cn.hutool.core.lang.Dict;
import cn.hutool.json.JSONUtil;

/**
 * 消息载荷属性提取器
 * <p>
 * 为异构系统接入提供支持。当消息信封缺失业务类型标识时，
 * 可通过提取策略从原始载荷中解析出具体的业务特征，协助调度引擎完成逻辑路由。
 *
 * @author jay.wu
 */
public interface MessageExtractor {

    /**
     * 依据指定提取算法从原始数据中获取消息类型标识
     *
     * @param rawPayload 原始数据（如 JSON 文本或二进制数据）
     * @return 业务类型全路径或唯一标识
     */
    String extractType(Object rawPayload);

    /**
     * 基于 JSON 文本属性的消息类型提取实现
     * <p>
     * 支持通过 JSON 属性路径（如 "header.eventCode"）快速获取消息特征。
     */
    class JsonPropertyExtractor implements MessageExtractor {

        private final String typePropertyPath;

        /**
         * @param typePropertyPath 目标类型所在的 JSON 路径
         */
        public JsonPropertyExtractor(String typePropertyPath) {
            this.typePropertyPath = typePropertyPath;
        }

        @Override
        public String extractType(Object rawPayload) {
            if (rawPayload instanceof String) {
                // 将原始文本转换为字典结构，支持深层嵌套属性的自动寻址
                Dict dict = JSONUtil.toBean((String) rawPayload, Dict.class);
                return dict.getByPath(typePropertyPath, String.class);
            }
            return null;
        }
    }
}
