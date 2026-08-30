package com.team4u.framework.flow.durable;

/**
 * 流程状态编解码 SPI：负责在 Durable 检查点将 Java 对象编码为 {@link StoredValue}，或从快照解码出全新 Java 对象。
 *
 * @author jay.wu
 */
public interface StateMapper {

    /**
     * 将业务对象编码为可存储的值。
     *
     * @param value 业务对象
     * @return 存储值包装对象
     * @throws Exception 编码异常
     */
    StoredValue encode(Object value) throws Exception;

    /**
     * 将存储值解码为业务对象。
     *
     * @param storedValue 存储值包装对象
     * @return 解码后的业务对象
     * @throws Exception 解码异常
     */
    Object decode(StoredValue storedValue) throws Exception;
}
