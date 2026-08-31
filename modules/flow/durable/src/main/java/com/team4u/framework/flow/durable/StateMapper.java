package com.team4u.framework.flow.durable;

/**
 * 业务数据与耐久化存储二进制块映射器 SPI 接口（State Mapper SPI）。
 *
 * <p>负责将内存中的应用状态对象序列化为 {@link StoredValue}，以及反序列化回内存对象。</p>
 *
 * <p><b>确定性编码契约（Determinism Contract）：</b><br>
 * 相同状态值在多次调用 {@link #encode} 时，必须产生 {@code equals} 完全相等的 {@link StoredValue}（即相同的 codec 标识与逐字节完全相同的二进制序列）。
 * 引擎对外部恢复信号（Resume Signal）的幂等性比较（同信号重复恢复重驱动、不同信号触发 {@link DurableException.Error#RESUME_SIGNAL_CONFLICT}）完全依赖该确定性保证。
 * 实现自定义 StateMapper 时切勿在载荷中包含随机盐、当前时间戳或未排序的 Map 键值对。</p>
 *
 * @author jay.wu
 */
public interface StateMapper {

    /**
     * 将业务对象编码为持久化存储值。
     *
     * @param value 业务对象，可能为 null
     * @return 存储值对象
     * @throws Exception 当序列化失败时抛出
     */
    StoredValue encode(Object value) throws Exception;

    /**
     * 从持久化存储值解码恢复为业务对象。
     *
     * @param storedValue 存储值对象，不能为 null
     * @return 解码后的业务对象
     * @throws Exception 当反序列化失败时抛出
     */
    Object decode(StoredValue storedValue) throws Exception;
}

