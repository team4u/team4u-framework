package com.team4u.framework.log.core;

/**
 * 日志序列化器接口
 * <p>
 * 抽象底层的序列化实现（如 Jackson, Gson 等），解耦核心引擎与具体库。
 */
public interface LogSerializer {

    /**
     * 将日志事件对象转换为 JSON 字符串
     *
     * @param event 日志事件
     * @return JSON 字符串
     */
    String serialize(LogEvent event);

    /**
     * 重置序列化器状态及缓存
     */
    void reset();
}
