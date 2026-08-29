package com.team4u.framework.singleflight.config;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 回源合并规则（配置驱动，配置键 {@code team4u.singleflight.{point}} 对应一条 JSON）。
 * <p>
 * 一个 point 声明一组合并语义：key 模板决定“哪些调用算同一件事”，skipWhen 决定
 * “哪些调用绕过合并”，contention 决定“竞争者如何收尾”，缓存三参数决定
 * “结果复用多久”。实例仅在 JSON 反序列化期间可变，校验通过后即被引擎视为
 * 不可变的运行期配置；配置热更新时新规则编译成功才替换旧规则。
 * </p>
 *
 * @author jay.wu
 */
@Data
public class SingleFlightRule {

    /**
     * 规则标识，必须与 point 完全一致（执行期校验），加载期不允许为空。
     */
    private String id;

    /**
     * 是否启用本规则。false 时直接执行加载函数，不读锁、会话与缓存。
     */
    private boolean enabled = true;

    /**
     * 命名存储（注册于 {@code SingleFlightStores}）；空白表示引擎默认存储。
     * 规则生效后不允许热切换存储。
     */
    private String store;

    /**
     * key 模板（如 {@code ${productId}}），变量取自参数名 Map；
     * 不配置时业务 key 就是 point（同 point 全局共享一个执行窗口）。
     * point 始终参与最终 key，不同 point 天然隔离。
     */
    private String key;

    /**
     * 跳过条件，Criterion 表达式，匹配参数名 Map。命中则直接执行加载函数，
     * 完全绕过协调与缓存；空白表示从不跳过。
     */
    private String skipWhen;

    /**
     * 缓存条件，Criterion 表达式，匹配加载返回值。为 false 时发布
     * {@code SUCCESS_NOT_CACHEABLE} 终态且不写结果缓存；空白表示默认可缓存。
     */
    private String cacheWhen;

    /**
     * 锁竞争策略：WAIT 轮询等待 / FAIL_FAST 快速失败 / FALLBACK 返回降级值。
     */
    private ContentionPolicy contention = ContentionPolicy.WAIT;

    /**
     * 降级值（原生 JSON），竞争策略为 FALLBACK 时按返回类型反序列化。
     * 配置文件中省略该字段表示禁用（FALLBACK 省略会加载失败）；
     * 显式 JSON {@code null} 表示降级返回 null，与省略语义不同。
     */
    private JsonNode fallback;

    /**
     * 组件失败兑底值（原生 JSON）：FAIL_FAST 竞争、WAIT 超时、复用失败会话
     * 三类组件异常不抛出，改为按返回类型反序列化此值返回。
     * 省略表示不兑底（异常照抛）；显式 JSON {@code null} 表示返回 null（仅对象类型）。
     * 不覆盖配置错误（SingleFlightConfigException）与 loader 业务异常。
     */
    private JsonNode errorFallback;

    /**
     * kv 锁租约（毫秒）。持有期间锁管理器后台续约；进程崩溃后续约停止，
     * 租约到期后锁可被等待者接管。
     */
    private long lockLeaseMillis = 30_000;

    /**
     * WAIT 调用者等待终态或接管机会的最长时间（毫秒），超时抛超时异常。
     */
    private long waitTimeoutMillis = 10_000;

    /**
     * WAIT 轮询会话与锁记录的间隔（毫秒）。
     */
    private long pollIntervalMillis = 100;

    /**
     * 是否启用结果缓存。false 时 cacheTtlMillis 必须为 0，锁与会话协调仍然生效。
     */
    private boolean cacheEnabled = true;

    /**
     * 结果缓存 TTL（毫秒）。cacheEnabled=true 时必须大于 0；false 时必须为 0。
     */
    private long cacheTtlMillis;

    /**
     * 成功终态会话 TTL（毫秒）：可缓存与不可缓存成功均使用。
     * 不可缓存成功不写结果缓存，仅让等待者在该窗口内读到本次结果。
     */
    private long uncacheableTtlMillis = 5_000;

    /**
     * 失败终态会话 TTL（毫秒）：窗口内同 key 的 WAIT 调用者收到重构的失败异常，
     * 避免失败风暴重复回源。
     */
    private long failureTtlMillis = 5_000;

    /**
     * 规则缺失策略。注意：仅通过全局配置键 {@code team4u.singleflight.on_rule_missing}
     * 生效，规则 JSON 内的同名字段不参与裁决。
     */
    private RuleMissingPolicy onRuleMissing = RuleMissingPolicy.PASS_THROUGH;

    /**
     * key 渲染失败策略：ERROR 抛配置异常；PASS_THROUGH 不做协调直接执行加载函数。
     */
    private InvalidKeyPolicy onInvalidKey = InvalidKeyPolicy.ERROR;

    /**
     * 存储故障策略（可选显式配置）。省略时按竞争策略推导：
     * FAIL_FAST 默认 FAIL_CLOSED，WAIT / FALLBACK 默认 PASS_THROUGH。
     */
    private StoreFailurePolicy onStoreFailure;

    /**
     * key 摘要算法名（注册于 {@code SingleFlightKeyDigests.global()}）。
     * 空白表示不摘要，业务 key 明文进入存储；未注册的名字在规则加载期失败。
     * 摘要只由本字段手工指定，不再按 key 长度自动触发。
     */
    private String keyDigest;
}
