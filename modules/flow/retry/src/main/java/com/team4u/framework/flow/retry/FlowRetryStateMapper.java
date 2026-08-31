package com.team4u.framework.flow.retry;

import com.team4u.framework.flow.durable.snapshot.StateMapper;
import com.team4u.framework.flow.durable.snapshot.StoredValue;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@link FlowRetryState} 的手工编解码 StateMapper（无第三方序列化依赖）。
 *
 * <p>以十进制文本编码唯一的 {@code attempt} 字段（如 {@code "3"}），codecId 为
 * {@code "flow-retry-attempt"}、版本 1。编码确定（相同状态逐字节相同），可直接与
 * {@code CompositeStateMapper.withDefault(...)} 组合，也可单独作为 Durable 引擎的
 * {@code stateMapper} 使用，让 {@link FlowRetryPolicy} 的重试状态获得开箱即用的持久化能力。</p>
 *
 * <p>注意：本类位于 team4u-flow-retry 模块，依赖 team4u-flow-durable 的 StateMapper SPI；
 * 该 SPI 由 team4u-flow（生产依赖）传递提供。</p>
 *
 * @author jay.wu
 */
public final class FlowRetryStateMapper implements StateMapper {

    /** 编解码器唯一标识。 */
    public static final String CODEC_ID = "flow-retry-attempt";

    /** 编码版本号。 */
    public static final int CODEC_VERSION = 1;

    /** 单例实例（无状态，可安全共享）。 */
    public static final FlowRetryStateMapper INSTANCE = new FlowRetryStateMapper();

    private FlowRetryStateMapper() {
    }

    @Override
    public StoredValue encode(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        if (!(value instanceof FlowRetryState)) {
            throw new IllegalArgumentException("FlowRetryStateMapper supports only FlowRetryState but got: "
                    + value.getClass().getName());
        }
        byte[] payload = String.valueOf(((FlowRetryState) value).getAttempt())
                .getBytes(StandardCharsets.UTF_8);
        return new StoredValue(CODEC_ID, CODEC_VERSION, payload);
    }

    @Override
    public Object decode(StoredValue storedValue) {
        Objects.requireNonNull(storedValue, "storedValue must not be null");
        if (!CODEC_ID.equals(storedValue.codecId())) {
            throw new IllegalArgumentException("Unsupported codecId: " + storedValue.codecId()
                    + " (expected: " + CODEC_ID + ")");
        }
        if (storedValue.codecVersion() != CODEC_VERSION) {
            throw new IllegalArgumentException("Unsupported codec version: " + storedValue.codecVersion()
                    + " (expected: " + CODEC_VERSION + ")");
        }
        String text = new String(storedValue.payload(), StandardCharsets.UTF_8);
        try {
            return new FlowRetryState(Integer.parseInt(text));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Corrupted FlowRetryState payload: " + text, error);
        }
    }
}
