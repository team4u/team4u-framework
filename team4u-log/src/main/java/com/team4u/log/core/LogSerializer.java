package com.team4u.log.core;

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
     * 设置全局日志序列化最大长度阈值
     *
     * @param maxLogLength 最大长度
     */
    void setMaxLogLength(int maxLogLength);

    /**
     * 获取全局日志序列化最大长度阈值
     *
     * @return 最大长度
     */
    int getMaxLogLength();

    /**
     * 设置单个字符串字段的最大长度
     *
     * @param maxStringLength 最大长度
     */
    void setMaxStringLength(int maxStringLength);

    /**
     * 获取单个字符串字段的最大长度
     *
     * @return 最大长度
     */
    int getMaxStringLength();

    /**
     * 重置序列化器状态及缓存
     */
    void reset();
}
