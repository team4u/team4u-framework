package com.team4u.framework.singleflight.core;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import com.team4u.framework.singleflight.api.SingleFlightConflictException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlightExecutionException;
import com.team4u.framework.singleflight.api.SingleFlightTimeoutException;
import com.team4u.framework.singleflight.config.InvalidKeyPolicy;
import com.team4u.framework.singleflight.config.RuleMissingPolicy;
import com.team4u.framework.singleflight.config.StoreFailurePolicy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Objects;

/**
 * 回源合并（singleflight）协调引擎：同一 key 的并发调用只允许一个执行者真正回源，
 * 其余调用者按规则竞争策略以等待复用、快速失败或降级值收尾。
 * <p>
 * 引擎是执行流程的编排层——规则加载（配置键 {@code team4u.singleflight.{point}}）→
 * 请求校验 → skipWhen 跳过判断 → key 渲染 → 委托 {@link SessionCoordinator} 完成锁协调。
 * 协作分工：
 * </p>
 * <ul>
 *     <li>{@link RuleCompiler}：规则 JSON → {@link CompiledRule}（校验、存储解析、条件预编译）</li>
 *     <li>{@link SessionCoordinator}：单个 key 上的执行窗口状态机（抢锁、终态发布、等待与接管）</li>
 *     <li>{@link ResultCodec}：加载结果与 kv 存储之间的 JSON 序列化边界</li>
 *     <li>{@link EffectivePolicies}：显式配置优先、省略按语义推导的生效策略</li>
 * </ul>
 * <p>
 * 协调状态全部落在底层 {@link KvStore} 的三个 space（{@value LOCK_SPACE} /
 * {@value SESSION_SPACE} / {@value CACHE_SPACE}），跨线程、跨实例共享同一执行窗口。
 * 存储故障按规则的 {@code onStoreFailure} 策略处置。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public class SingleFlightEngine implements AutoCloseable {

    /**
     * 规则配置模式：配置键 team4u.singleflight.{point}，一个 point 对应一条 JSON 规则
     */
    public static final String DEFAULT_CONFIG_PATTERN = "team4u.singleflight.*";

    /**
     * 全局规则缺失策略配置键（规则 JSON 内的同名字段不参与裁决，只有此全局键生效）
     */
    public static final String GLOBAL_RULE_MISSING_KEY = "team4u.singleflight.on_rule_missing";

    /**
     * 执行权互斥锁 space
     */
    public static final String LOCK_SPACE = "singleflight.lock";

    /**
     * 会话信封 space
     */
    public static final String SESSION_SPACE = "singleflight.session";

    /**
     * 结果缓存 space
     */
    public static final String CACHE_SPACE = "singleflight.cache";

    private final ConfigManager configManager;
    /**
     * 规则注册表：配置驱动加载 + 热更新安全替换（新规则编译成功才替换旧规则）
     */
    private final ConfigDrivenRegistry<CompiledRule> rules;
    private final RuleCompiler ruleCompiler;
    private final SessionCoordinator coordinator;

    public SingleFlightEngine(ConfigManager configManager, KvStore defaultStore) {
        this(configManager, defaultStore, Clock.systemUTC());
    }

    public SingleFlightEngine(ConfigManager configManager, KvStore defaultStore, Clock clock) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        Objects.requireNonNull(defaultStore, "defaultStore");
        Objects.requireNonNull(clock, "clock");
        this.ruleCompiler = new RuleCompiler(defaultStore, clock);
        this.coordinator = new SessionCoordinator(clock);
        this.rules = new ConfigDrivenRegistry<>(configManager, DEFAULT_CONFIG_PATTERN,
                ruleCompiler::compile);
    }

    /**
     * 执行一次回源合并请求：完成规则裁决、key 渲染、缓存读取与锁协调的全流程。
     *
     * @param execution 执行请求（point、参数上下文、返回类型与加载函数）
     * @param <T>       加载函数返回类型
     * @return 本地或复用（缓存 / 等待 / 降级）的执行结果
     * @throws SingleFlightConfigException      point 为空、规则 id 与 point 不一致、规则缺失（ERROR 策略）等配置问题
     * @throws SingleFlightConflictException    竞争策略为 FAIL_FAST 且锁被他人持有
     * @throws SingleFlightTimeoutException     WAIT 等待终态或接管机会超时
     * @throws SingleFlightExecutionException   复用到其他执行者发布的失败会话
     */
    public <T> T execute(SingleFlightExecution<T> execution) {
        if (execution.getPoint() == null || execution.getPoint().trim().isEmpty()) {
            throw new SingleFlightConfigException("Singleflight point must not be empty");
        }
        CompiledRule rule = rules.get(execution.getPoint());
        if (rule == null) {
            return onRuleMissing(execution);
        }
        // id 必须与 point 完全一致，防止配置键与规则体错位导致跨 point 误合并
        if (!execution.getPoint().equals(rule.rule().getId())) {
            throw new SingleFlightConfigException("Singleflight rule id must match point"
                    + "|point=" + execution.getPoint() + "|id=" + rule.rule().getId());
        }
        // enabled=false：完全绕过协调与缓存，直接执行加载函数
        if (!rule.rule().isEnabled()) {
            return load(execution);
        }
        // 提前校验 skipWhen 变量可解析性，让配置错误在执行期第一时间暴露
        EffectivePolicies.validateCriterionVariables(rule.skipWhen(), execution, "skipWhen");
        EffectivePolicies.validateRuleForExecution(rule.rule(), execution);
        if (rule.skipWhen() != null && EffectivePolicies.matchesSkip(rule, execution)) {
            return load(execution);
        }

        String key = renderKey(rule, execution);
        // key 渲染失败且策略为 PASS_THROUGH：不做协调，直接执行加载函数
        if (key == null) {
            return load(execution);
        }
        try {
            return coordinator.executeWithKey(rule, execution, key);
        } catch (KvStoreException e) {
            return onStoreFailure(rule, execution, e);
        }
    }

    /**
     * 规则缺失时的收尾：ERROR 抛配置异常，PASS_THROUGH 记 warn 后直接执行加载函数。
     */
    private <T> T onRuleMissing(SingleFlightExecution<T> execution) {
        RuleMissingPolicy policy = globalRuleMissingPolicy();
        if (policy == RuleMissingPolicy.ERROR) {
            throw new SingleFlightConfigException(
                    "Singleflight rule is missing|point=" + execution.getPoint());
        }
        log.warn("SingleFlightEngine|ruleMissingPassThrough|point={}", execution.getPoint());
        return load(execution);
    }

    /**
     * 读取全局规则缺失策略配置；缺省 PASS_THROUGH，取值不合法视为配置错误。
     */
    private RuleMissingPolicy globalRuleMissingPolicy() {
        String value = configManager.getString(GLOBAL_RULE_MISSING_KEY).orElse(null);
        if (value == null || value.trim().isEmpty()) {
            return RuleMissingPolicy.PASS_THROUGH;
        }
        try {
            return RuleMissingPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new SingleFlightConfigException(
                    "Invalid onRuleMissing policy|value=" + value, e);
        }
    }

    /**
     * 渲染最终协调 key：未配置模板时以 point 为业务 key（同 point 全局共享窗口）；
     * 渲染结果为 null 时按 onInvalidKey 策略处置（PASS_THROUGH 返回 null 由调用方直接回源）；
     * 最终经 {@link SingleFlightKeys} 完成 point 拼接、编码与按需摘要。
     */
    private String renderKey(CompiledRule rule, SingleFlightExecution<?> execution) {
        String rendered;
        if (rule.keyResolver() == null) {
            rendered = execution.getPoint();
        } else {
            rendered = rule.keyResolver().render(execution.getArguments());
        }
        if (rendered == null) {
            if (rule.rule().getOnInvalidKey() == InvalidKeyPolicy.PASS_THROUGH) {
                return null;
            }
            throw new SingleFlightConfigException("Singleflight rendered key is invalid"
                    + "|point=" + execution.getPoint() + "|template=" + rule.rule().getKey());
        }
        return SingleFlightKeys.compose(execution.getPoint(), rendered, rule.keyDigest());
    }

    /**
     * 直接执行加载函数（跳过 / 缓存 / 直通路径共用），业务异常原样抛出。
     */
    private <T> T load(SingleFlightExecution<T> execution) {
        try {
            return execution.getLoader().load();
        } catch (Throwable throwable) {
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            }
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }
            throw new IllegalStateException(throwable);
        }
    }

    /**
     * 协调存储故障收尾：FAIL_CLOSED 立即失败，PASS_THROUGH 记 warn 后直接执行加载函数。
     */
    private <T> T onStoreFailure(CompiledRule rule, SingleFlightExecution<T> execution,
                                 KvStoreException e) {
        if (EffectivePolicies.storeFailure(rule.rule()) == StoreFailurePolicy.FAIL_CLOSED) {
            throw new SingleFlightConfigException("Singleflight store failure|point="
                    + execution.getPoint(), e);
        }
        log.warn("SingleFlightEngine|storeFailurePassThrough|point={}",
                execution.getPoint(), e);
        return load(execution);
    }

    /**
     * 释放引擎资源：注销规则监听、关闭编译器（含全部锁管理器）；可重复调用。
     */
    @Override
    public void close() {
        rules.destroy();
        ruleCompiler.close();
    }

    public void destroy() {
        close();
    }
}
