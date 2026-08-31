package com.team4u.framework.flow.durable;

/**
 * Maps application state to and from an opaque durable value.
 *
 * <p><b>确定性契约</b>：同一状态值多次 {@link #encode} 必须产生 {@code equals}
 * 相等的 {@link StoredValue}（相同 codec 标识与字节序列）。resume 信号的幂等比较
 * （同值重驱动、异值 RESUME_SIGNAL_CONFLICT）依赖编码的确定性；不确定编码
 * （如包含随机标识、时间戳或哈希迭代序的 payload）会破坏幂等语义。</p>
 */
public interface StateMapper {
    StoredValue encode(Object value) throws Exception;

    Object decode(StoredValue storedValue) throws Exception;
}
