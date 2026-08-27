package com.team4u.framework.kv.observed;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.StoreWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 可观测键值存储装饰器：结构化操作日志、慢操作告警、值脱敏视图
 * <p>
 * 为每次操作输出结构化审计日志（操作类型、键、值长度、脱敏值摘要、耗时、结果），
 * 超过慢操作阈值时升级为 warn，操作失败时记录 error。
 * 值脱敏通过 {@link ValueMasker} 函数接口接入（默认不脱敏），
 * 可与脱敏组件（如 {@code FastMasker}）以适配器形式桥接，
 * 保持本模块零强依赖。
 * </p>
 * 日志级别约定：常规操作 debug、慢操作 warn、失败 error——生产环境按需调整
 * {@code com.team4u.framework.kv.observed} 的日志级别即可开启/关闭审计。
 * 支持能力解析（见 {@link com.team4u.framework.kv.KvStores}）：实现
 * {@link StoreWrapper} 暴露内层存储，锁管理器等能力协商组件可穿透本装饰层。
 *
 * @author jay.wu
 */
@Slf4j
public class ObservedStore implements KvStore, StoreWrapper {


    private final KvStore delegate;
    private final Config config;
    private final ValueMasker masker;

    public ObservedStore(KvStore delegate) {
        this(delegate, new Config(), ValueMasker.NONE);
    }

    public ObservedStore(KvStore delegate, Config config, ValueMasker masker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.config = Objects.requireNonNull(config, "config");
        this.masker = Objects.requireNonNull(masker, "masker");
    }

    @Override
    public KvStore unwrap() {
        return delegate;
    }

    @Override
    public KvRecord get(SpaceKey key) {
        long start = System.currentTimeMillis();
        try {
            KvRecord record = delegate.get(key);
            opLog("get", key, record == null ? null : record.getValue(),
                    start, record != null);
            return record;
        } catch (RuntimeException e) {
            failLog("get", key, start, e);
            throw e;
        }
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        long start = System.currentTimeMillis();
        try {
            boolean success = delegate.put(key, record, mode);
            opLog("put" + ":" + mode.name().toLowerCase(), key,
                    success ? record.getValue() : null, start, success);
            return success;
        } catch (RuntimeException e) {
            failLog("put", key, start, e);
            throw e;
        }
    }

    @Override
    public boolean remove(SpaceKey key) {
        long start = System.currentTimeMillis();
        try {
            boolean removed = delegate.remove(key);
            opLog("remove", key, null, start, removed);
            return removed;
        } catch (RuntimeException e) {
            failLog("remove", key, start, e);
            throw e;
        }
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        long start = System.currentTimeMillis();
        try {
            boolean renewed = delegate.expire(key, ttlMillis);
            opLog("expire", key, null, start, renewed);
            return renewed;
        } catch (RuntimeException e) {
            failLog("expire", key, start, e);
            throw e;
        }
    }

    private void opLog(String op, SpaceKey key, String value, long start, boolean hit) {
        // 日志未启用时跳过脱敏与截断计算，避免热路径白算
        if (!log.isDebugEnabled() && !log.isWarnEnabled()) {
            return;
        }
        long cost = System.currentTimeMillis() - start;
        boolean slow = cost >= config.getSlowOpThresholdMillis();
        String masked = excerpt(value == null ? null : masker.mask(key, value));
        if (slow) {
            log.warn("KV_SLOW|{}|{}|{}|{}|{}|{}", op, key, hit,
                    value == null ? -1 : value.length(), masked, cost);
        } else {
            log.debug("KV|{}|{}|{}|{}|{}|{}", op, key, hit,
                    value == null ? -1 : value.length(), masked, cost);
        }
    }

    private void failLog(String op, SpaceKey key, long start, RuntimeException e) {
        log.error("KV_FAIL|{}|{}|costMs={}|error={}", op, key,
                System.currentTimeMillis() - start, e.getMessage(), e);
    }

    /**
     * 日志中的值摘要：先脱敏再按最大长度截断，null 表示无值
     */
    private String excerpt(String maskedValue) {
        if (maskedValue == null) {
            return null;
        }
        int max = config.getMaxValueLogLength();
        return maskedValue.length() <= max ? maskedValue
                : maskedValue.substring(0, max) + "...(" + maskedValue.length() + ")";
    }

    /**
     * 值脱敏器：日志输出前的值视图变换
     *
     * @author jay.wu
     */
    @FunctionalInterface
    public interface ValueMasker {

        /**
         * 不脱敏
         */
        ValueMasker NONE = (key, value) -> value;

        /**
         * 对值做脱敏/摘要，用于日志展示
         */
        String mask(SpaceKey key, String value);
    }

    /**
     * 可观测配置
     *
     * @author jay.wu
     */
    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class Config {

        /**
         * 日志中值摘要的默认最大长度
         */
        public static final int DEFAULT_MAX_VALUE_LOG_LENGTH = 200;

        /**
         * 慢操作默认阈值（毫秒）
         */
        public static final long DEFAULT_SLOW_OP_THRESHOLD_MILLIS = 100;

        /**
         * 日志中值摘要的最大长度，超出截断（借鉴 log 组件的 FinOps 成本保护）
         */
        private int maxValueLogLength = DEFAULT_MAX_VALUE_LOG_LENGTH;

        /**
         * 慢操作阈值（毫秒），达到即升级为 warn
         */
        private long slowOpThresholdMillis = DEFAULT_SLOW_OP_THRESHOLD_MILLIS;
    }
}
