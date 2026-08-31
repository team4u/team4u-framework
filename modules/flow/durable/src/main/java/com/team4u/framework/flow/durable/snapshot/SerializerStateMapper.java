package com.team4u.framework.flow.durable.snapshot;

import java.util.Objects;
import java.util.function.Function;

/**
 * 通用序列化器桥接数据映射器（Serializer-Backed State Mapper）。
 *
 * <p>支持将流程业务数据通过外部序列化组件（如 Jackson、Fastjson 等）
 * 编解码为二进制 byte[] 存储在快照槽位中，实现流执行器与具体序列化框架的解耦。</p>
 *
 * @author jay.wu
 */
public final class SerializerStateMapper implements StateMapper {
    private final String codecId;
    private final int version;
    private final Function<Object, byte[]> serializer;
    private final Function<byte[], Object> deserializer;

    /**
     * 创建序列化器桥接映射器。
     *
     * @param codecId      编解码器唯一标识（如 "json:jackson"）
     * @param version      编码版本号
     * @param serializer   序列化函数
     * @param deserializer 反序列化函数
     */
    public SerializerStateMapper(String codecId, int version,
                                 Function<Object, byte[]> serializer,
                                 Function<byte[], Object> deserializer) {
        this.codecId = Objects.requireNonNull(codecId, "codecId must not be null");
        this.version = version;
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer must not be null");
    }

    @Override
    public StoredValue encode(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        byte[] payload = serializer.apply(value);
        return new StoredValue(codecId, version, payload);
    }

    @Override
    public Object decode(StoredValue storedValue) {
        Objects.requireNonNull(storedValue, "storedValue must not be null");
        if (!codecId.equals(storedValue.codecId())) {
            throw new IllegalArgumentException("Unsupported codecId: " + storedValue.codecId());
        }
        if (storedValue.codecVersion() != version) {
            throw new IllegalArgumentException("Unsupported codec version: " + storedValue.codecVersion());
        }
        return deserializer.apply(storedValue.payload());
    }
}
