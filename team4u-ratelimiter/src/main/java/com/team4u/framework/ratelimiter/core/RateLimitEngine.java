package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.base.util.ReflectUtil;
import com.team4u.framework.base.util.TextTemplate;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.ratelimiter.api.RateLimitConfigException;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimitResult;
import com.team4u.framework.ratelimiter.config.RateLimitRule;
import com.team4u.framework.ratelimiter.store.RateLimitStores;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流引擎：检查点 → 规则组 → 按优先级逐条裁决
 * <p>
 * 组装四个关注点，各司其职、均为框架既有能力：
 * </p>
 * <ul>
 *     <li><b>规则加载</b>：{@link ConfigDrivenRegistry} 按配置键
 *     {@code team4u.ratelimiter.{point}} 加载该检查点的规则列表（JSON 数组），
 *     热更新安全替换（先建新再替换、失败保旧）</li>
 *     <li><b>算法路由</b>：{@link KeyedPolicyRegistry} 按算法名查表，
 *     预注册四个内置算法，自定义算法注册后即可在规则中按名引用</li>
 *     <li><b>键渲染</b>：{@link TextTemplate} 渲染规则键模板（上下文为 Map 取值、
 *     Bean 反射读公有 getter，变量缺失渲染为空串），模板按内容缓存</li>
 *     <li><b>存储协商</b>：无状态算法不解析存储；其余按 {@link RateLimitStores}
 *     按名解析（空名 = 默认存储），所需能力在规则加载期经
 *     {@code KvStores.capabilityOf} 逐一校验</li>
 * </ul>
 * <p>
 * 裁决流程：规则列表按 priority 升序稳定排序（越小优先级越高，与策略组件 ContextPolicy 约定一致），逐条执行、首拒即停；
 * 全部通过返回最后一条通过规则的结果。存储故障按规则 {@code failOpen} 处置：
 * true 记 warn 后视为该条通过继续，false 立即返回 STORE_ERROR 拒绝。
 * </p>
 * <p>
 * 计数键为 {@code rl:{规则标识}.{渲染后的键}}；渲染结果中的 ':' 替换为 '_'
 * （SpaceKey 约束键内不允许出现 ':'，替换保证任意上下文值均可安全组键）。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public class RateLimitEngine implements AutoCloseable {

    /**
     * 默认规则配置模式：配置键 team4u.ratelimiter.{point}
     */
    public static final String DEFAULT_CONFIG_PATTERN = "team4u.ratelimiter.*";

    /**
     * 默认计数键空间
     */
    public static final String DEFAULT_SPACE = "rl";

    private final KvStore defaultStore;
    private final Clock clock;
    private final KeyedPolicyRegistry<String, RateLimitAlgorithm> algorithms;
    private final ConfigDrivenRegistry<List<RateLimitRule>> rules;

    /**
     * 模板缓存：模板不可变，按内容缓存避免重复解析
     */
    private final Map<String, TextTemplate> templates = new ConcurrentHashMap<>();

    public RateLimitEngine(ConfigManager configManager, KvStore defaultStore) {
        this(configManager, defaultStore, Clock.systemUTC());
    }

    /**
     * @param clock 时钟；测试可注入虚拟时钟
     */
    public RateLimitEngine(ConfigManager configManager, KvStore defaultStore, Clock clock) {
        this.defaultStore = Objects.requireNonNull(defaultStore, "defaultStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.algorithms = new KeyedPolicyRegistry<>(RateLimitAlgorithm.class);
        algorithms.addAll(defaultAlgorithms());
        this.rules = new ConfigDrivenRegistry<>(configManager, DEFAULT_CONFIG_PATTERN,
                json -> parseRules(json, algorithms, defaultStore));
    }

    /**
     * 内置算法注册表（可按需替换或追加）
     */
    protected List<RateLimitAlgorithm> defaultAlgorithms() {
        List<RateLimitAlgorithm> builtins = new ArrayList<>(4);
        builtins.add(new FixedWindowAlgorithm());
        builtins.add(new TokenBucketAlgorithm());
        builtins.add(new SlidingWindowAlgorithm());
        builtins.add(new HistoryWindowAlgorithm());
        return builtins;
    }

    /**
     * 算法注册表（注册自定义算法用）
     */
    public KeyedPolicyRegistry<String, RateLimitAlgorithm> algorithms() {
        return algorithms;
    }

    // ------------------------------------------------- 检查

    /**
     * 限流检查（1 个许可）
     */
    public RateLimitResult acquire(String point, Object context) {
        return acquire(point, context, 1);
    }

    /**
     * 限流检查（指定许可数，0 = 窥探：仅计数不占用）
     */
    public RateLimitResult acquire(String point, Object context, int permits) {
        if (point == null || point.trim().isEmpty()) {
            throw new RateLimitConfigException("Rate limit point must not be empty");
        }
        if (permits < 0) {
            throw new IllegalArgumentException("permits must be >= 0|point=" + point);
        }
        long now = clock.millis();
        List<RateLimitRule> pointRules = rules.get(point);
        if (pointRules == null || pointRules.isEmpty()) {
            return RateLimitResult.allowedNoRule(point, now);
        }

        RateLimitResult lastPassed = null;
        for (RateLimitRule rule : pointRules) {
            String key = composedKeyOf(point, rule, context);
            RateLimitAlgorithm algorithm = algorithmOf(point, rule);
            KvStore store = storeOf(rule, algorithm);

            RateLimitResult result;
            try {
                result = algorithm.tryAcquire(rule, rule.getConfig(), store, key, context, now, permits);
            } catch (KvStoreException e) {
                if (rule.isFailOpen()) {
                    log.warn("RateLimitEngine|failOpen|point={}|ruleId={}|key={}",
                            point, rule.getId(), key, e);
                    // 视为该条通过，继续下一条
                    lastPassed = RateLimitResult.builder()
                            .allowed(true)
                            .ruleId(rule.getId())
                            .decisionTimeMillis(now)
                            .reason(RateLimitReason.PASS)
                            .build();
                    continue;
                }
                log.warn("RateLimitEngine|failClosed|point={}|ruleId={}|key={}",
                        point, rule.getId(), key, e);
                return withPoint(RateLimitResult.builder()
                        .allowed(false)
                        .ruleId(rule.getId())
                        .decisionTimeMillis(now)
                        .reason(RateLimitReason.STORE_ERROR)
                        .build(), point);
            }

            log.debug("RateLimitEngine|acquire|point={}|rule={}|key={}|result={}",
                    point, rule.getId(), key, result.isAllowed() ? "allow" : "deny");
            if (!result.isAllowed()) {
                return withPoint(result, point);
            }
            lastPassed = result;
        }
        return withPoint(lastPassed, point);
    }

    /**
     * 是否放行（便捷入口）
     */
    public boolean tryAcquire(String point, Object context) {
        return acquire(point, context, 1).isAllowed();
    }

    /**
     * 销毁引擎：释放配置监听
     */
    public void destroy() {
        rules.destroy();
        templates.clear();
    }

    @Override
    public void close() {
        destroy();
    }

    // ------------------------------------------------- 规则与校验

    /**
     * 规则列表解析与校验（配置驱动注册表工厂，热更新失败保旧实例）
     * <p>
     * 校验项：列表非空、id 非空唯一且不含 ':'、算法已注册、windowMillis&gt;0、
     * threshold&gt;0、config 按算法声明的类型转换成功（算法不接受 config 时禁止携带）、
     * 有状态算法的存储可解析且具备全部所需能力、键模板不含 ':' 可被解析。
     * 任一失败抛 {@link RateLimitConfigException}。
     * </p>
     *
     * @return 按 priority 升序稳定排序（越小优先级越高）的不可变规则列表
     */
    static List<RateLimitRule> parseRules(String json,
                                          KeyedPolicyRegistry<String, RateLimitAlgorithm> algorithms,
                                          KvStore defaultStore) {
        List<RateLimitRule> parsed;
        try {
            parsed = JsonUtil.toList(json, RateLimitRule.class);
        } catch (RuntimeException e) {
            throw new RateLimitConfigException("Invalid rate limit rules json|json=" + json, e);
        }
        if (parsed == null || parsed.isEmpty()) {
            throw new RateLimitConfigException("Rate limit rules must not be empty|json=" + json);
        }

        Set<String> ids = new HashSet<>();
        for (RateLimitRule rule : parsed) {
            if (rule.getId() == null || rule.getId().trim().isEmpty()) {
                throw new RateLimitConfigException("Rate limit rule id must not be empty");
            }
            if (rule.getId().indexOf(':') >= 0) {
                throw new RateLimitConfigException("Rate limit rule id must not contain ':'|id=" + rule.getId());
            }
            if (!ids.add(rule.getId())) {
                throw new RateLimitConfigException("Rate limit rule id duplicated|id=" + rule.getId());
            }
            RateLimitAlgorithm algorithm = algorithms.get(rule.getAlgorithm())
                    .orElseThrow(() -> new RateLimitConfigException("Rate limit algorithm not found"
                            + "|id=" + rule.getId() + "|algorithm=" + rule.getAlgorithm()));
            if (rule.getWindowMillis() <= 0) {
                throw new RateLimitConfigException("Rate limit rule windowMillis must be > 0"
                        + "|id=" + rule.getId());
            }
            if (rule.getThreshold() <= 0) {
                throw new RateLimitConfigException("Rate limit rule threshold must be > 0"
                        + "|id=" + rule.getId());
            }
            boolean stateless = algorithm.requiredCapabilities().length == 0;
            if (!stateless) {
                validateStoreCapabilities(rule, algorithm, defaultStore);
            }
            rule.setConfig(configOf(rule, algorithm));
            if (rule.getKey() != null && rule.getKey().indexOf(':') >= 0) {
                throw new RateLimitConfigException("Rate limit rule key must not contain ':'"
                        + "|id=" + rule.getId() + "|key=" + rule.getKey());
            }
            templatesProbe(rule.getKey());
        }

        List<RateLimitRule> sorted = new ArrayList<>(parsed);
        sorted.sort(Comparator.comparingInt(RateLimitRule::getPriority));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * 触发一次模板解析以尽早暴露非法模板（当前实现解析宽容，此调用同时作为键模板的固定入口）
     */
    private static void templatesProbe(String key) {
        if (key != null) {
            new TextTemplate(key);
        }
    }

    /**
     * 规则 config 转换为算法声明的类型化实例并写回规则
     * <p>
     * 算法未声明配置类型（{@code Void}）时规则不得携带 config（防配置写错位置被静默忽略）；
     * 已声明但未配置时以无参构造取默认值（算法配置字段自带缺省，实现约定优于配置）。
     * </p>
     */
    private static Object configOf(RateLimitRule rule, RateLimitAlgorithm algorithm) {
        Class<?> configType = algorithm.configType();
        Object raw = rule.getConfig();
        if (configType == Void.class) {
            if (raw != null) {
                throw new RateLimitConfigException("Rate limit algorithm does not accept config"
                        + "|id=" + rule.getId() + "|algorithm=" + rule.getAlgorithm());
            }
            return null;
        }
        if (raw == null) {
            return ReflectUtil.newInstance(configType);
        }
        try {
            return JsonUtil.toBean(JsonUtil.toJsonStr(raw), configType);
        } catch (RuntimeException e) {
            throw new RateLimitConfigException("Invalid rate limit rule config"
                    + "|id=" + rule.getId() + "|algorithm=" + rule.getAlgorithm()
                    + "|configType=" + configType.getName(), e);
        }
    }

    /**
     * 有状态算法：存储可解析 + 所需能力齐备
     */
    private static void validateStoreCapabilities(RateLimitRule rule, RateLimitAlgorithm algorithm,
                                                  KvStore defaultStore) {
        KvStore store = resolveStore(rule.getStore(), defaultStore);
        for (Class<?> capability : algorithm.requiredCapabilities()) {
            if (KvStores.capabilityOf(store, capability) == null) {
                throw new RateLimitConfigException("Rate limit store not capable"
                        + "|id=" + rule.getId()
                        + "|store=" + (rule.getStore() == null || rule.getStore().trim().isEmpty()
                        ? "default:" + store.getClass().getName() : rule.getStore())
                        + "|capability=" + capability.getName());
            }
        }
    }

    /**
     * 规则存储解析：空名 = 默认存储；否则 RateLimitStores 按名解析
     *
     * @throws RateLimitConfigException 存储名未注册
     */
    private static KvStore resolveStore(String storeName, KvStore defaultStore) {
        if (storeName == null || storeName.trim().isEmpty()) {
            return defaultStore;
        }
        try {
            return RateLimitStores.global().resolve(storeName);
        } catch (IllegalArgumentException e) {
            throw new RateLimitConfigException("Rate limit store not registered|store=" + storeName, e);
        }
    }

    // ------------------------------------------------- 检查路径

    private RateLimitAlgorithm algorithmOf(String point, RateLimitRule rule) {
        return algorithms.get(rule.getAlgorithm())
                .orElseThrow(() -> new RateLimitConfigException("Rate limit algorithm not found"
                        + "|point=" + point + "|ruleId=" + rule.getId()
                        + "|algorithm=" + rule.getAlgorithm()));
    }

    /**
     * 检查时存储解析：无状态算法传 null；有状态算法按名解析（加载期已校验能力，此处仅查找）
     */
    private KvStore storeOf(RateLimitRule rule, RateLimitAlgorithm algorithm) {
        if (algorithm.requiredCapabilities().length == 0) {
            return null;
        }
        return resolveStore(rule.getStore(), defaultStore);
    }

    /**
     * 组合计数键：rule.key 空 → 检查点静态键；否则模板渲染（变量缺失渲染为空串）。
     * 渲染结果中的 ':' 替换为 '_'（SpaceKey 键内禁止 ':'）
     */
    private String composedKeyOf(String point, RateLimitRule rule, Object context) {
        String rawKey;
        if (rule.getKey() == null || rule.getKey().trim().isEmpty()) {
            rawKey = point;
        } else {
            rawKey = templates.computeIfAbsent(rule.getKey(), TextTemplate::new)
                    .render(name -> {
                        Object value = resolveVariable(context, name);
                        return value == null ? "" : String.valueOf(value);
                    });
        }
        return (rule.getId() + "." + rawKey).replace(':', '_');
    }

    /**
     * 上下文变量解析：Map 直接取值；Bean 反射读公有 getter（getXxx/isXxx，无 getter 返回 null）
     */
    private static Object resolveVariable(Object context, String name) {
        if (context == null || name == null || name.isEmpty()) {
            return null;
        }
        if (context instanceof Map) {
            return ((Map<?, ?>) context).get(name);
        }
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[]{"get", "is"}) {
            Method getter = ReflectUtil.getMethod(context.getClass(), prefix + capitalized);
            if (getter != null && Modifier.isPublic(getter.getModifiers())
                    && getter.getParameterCount() == 0 && getter.getReturnType() != void.class) {
                return ReflectUtil.invoke(context, getter);
            }
        }
        return null;
    }

    /**
     * 引擎负责补齐结果中的检查点（算法不感知检查点）
     */
    private static RateLimitResult withPoint(RateLimitResult result, String point) {
        return result.toBuilder().point(point).build();
    }
}
