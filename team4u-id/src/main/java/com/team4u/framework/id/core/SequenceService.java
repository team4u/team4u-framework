package com.team4u.framework.id.core;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.CacheUtil;
import com.team4u.framework.base.util.TextTemplate;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.id.api.SeqConfigException;
import com.team4u.framework.id.api.SeqExhaustedException;
import com.team4u.framework.id.api.Sequences;
import com.team4u.framework.id.config.SeqRule;
import com.team4u.framework.id.group.GroupKeyPolicies;
import com.team4u.framework.id.group.GroupKeyPolicy;
import com.team4u.framework.id.store.SeqStores;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Getter;

import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序号服务：{@link Sequences} 的默认实现
 * <p>
 * 组装四个关注点，各司其职、均为框架既有能力：
 * </p>
 * <ul>
 *     <li><b>规则加载</b>：{@link ConfigDrivenRegistry} 按配置键 {@code seq.{name}}
 *     加载 {@link SeqRule}，热更新安全替换（先建新再替换、失败保旧）</li>
 *     <li><b>分组</b>：{@link GroupKeyPolicy} 按策略计算分组标识，参与计数键组成，
 *     分组变化即重新计数</li>
 *     <li><b>计数</b>：{@link CounterCapable}（kv 能力协商解析）提供原子递增，
 *     存储无关——内存、JDBC、Redis 及其装饰器组合均可</li>
 *     <li><b>号段</b>：{@code segment > 0} 时经 {@link LocalSegment} 本地批量发号，
 *     实例由 LRU 缓存管理，随分组轮转与规则变更自然淘汰</li>
 * </ul>
 * 计数键为 {@code {键空间}:{规则标识}.{分组标识}}；耗尽与循环在取号层以
 * 等差数列纯算术换算，不回写存储。
 *
 * @author jay.wu
 */
@Getter
public class SequenceService implements Sequences {

    /**
     * 默认规则配置模式：配置键 seq.{name}
     */
    public static final String DEFAULT_CONFIG_PATTERN = "seq.*";

    /**
     * 默认计数键空间
     */
    public static final String DEFAULT_SPACE = "seq";

    /**
     * 默认号段实例缓存容量
     */
    public static final int DEFAULT_SEGMENT_CACHE_SIZE = 1024;

    private final ConfigDrivenRegistry<SeqRule> rules;
    private final KvStore defaultStore;
    private final String space;
    private final KeyedPolicyRegistry<String, GroupKeyPolicy> groupPolicies;
    private final Clock clock;
    private final Cache<String, LocalSegment> segments;
    private final Object segmentLock = new Object();

    /**
     * 模板缓存：模板不可变，按内容缓存避免重复解析
     */
    private final Map<String, TextTemplate> templates = new ConcurrentHashMap<>();

    public SequenceService(ConfigManager configManager, KvStore defaultStore) {
        this(configManager, defaultStore, DEFAULT_CONFIG_PATTERN, DEFAULT_SPACE,
                GroupKeyPolicies.global().registry(), Clock.systemUTC(), DEFAULT_SEGMENT_CACHE_SIZE);
    }

    /**
     * @param clock 时钟，供日期分组策略使用；测试可注入虚拟时钟
     */
    public SequenceService(ConfigManager configManager, KvStore defaultStore, Clock clock) {
        this(configManager, defaultStore, DEFAULT_CONFIG_PATTERN, DEFAULT_SPACE,
                GroupKeyPolicies.global().registry(), clock, DEFAULT_SEGMENT_CACHE_SIZE);
    }

    public SequenceService(ConfigManager configManager,
                           KvStore defaultStore,
                           String configPattern,
                           String space,
                           KeyedPolicyRegistry<String, GroupKeyPolicy> groupPolicies,
                           Clock clock,
                           int segmentCacheSize) {
        this.defaultStore = defaultStore;
        this.space = space;
        this.groupPolicies = groupPolicies;
        this.clock = clock;
        this.segments = CacheUtil.newLRUCache(segmentCacheSize);
        this.rules = new ConfigDrivenRegistry<>(configManager, configPattern, SequenceService::parseRule);
    }

    // ------------------------------------------------- 取号

    @Override
    public long next(String name) {
        return next(name, null);
    }

    @Override
    public long next(String name, Map<String, Object> ext) {
        Long value = tryNext(name, ext);
        if (value == null) {
            throw new SeqExhaustedException("Sequence exhausted|name=" + nameOf(name));
        }
        return value;
    }

    @Override
    public Long tryNext(String name) {
        return tryNext(name, null);
    }

    @Override
    public Long tryNext(String name, Map<String, Object> ext) {
        Issued issued = issue(name, ext);
        return issued == null ? null : issued.value;
    }

    @Override
    public String nextFormatted(String name) {
        return nextFormatted(name, null);
    }

    @Override
    public String nextFormatted(String name, Map<String, Object> ext) {
        Issued issued = issue(name, ext);
        if (issued == null) {
            throw new SeqExhaustedException("Sequence exhausted|name=" + nameOf(name));
        }
        return format(issued);
    }

    /**
     * 销毁服务：释放配置监听与号段缓存
     */
    public void destroy() {
        rules.destroy();
        segments.clear();
    }

    // ------------------------------------------------- 取号路径

    /**
     * 一次完整取号：规则 → 分组 → 计数位置 → 序号值
     *
     * @return 取号结果；上游耗尽返回 {@code null}
     */
    private Issued issue(String name, Map<String, Object> ext) {
        String ruleId = nameOf(name);
        SeqRule rule = ruleOf(ruleId);
        String groupKey = groupKeyOf(ruleId, rule, ext);
        Long count = availableCount(ruleId, rule);

        Long position = nextPosition(ruleId, rule, groupKey, count);
        if (position == null) {
            return null;
        }
        return new Issued(ruleId, groupKey, rule, valueOf(rule, count, position));
    }

    /**
     * 取 1-based 计数位置：直连或本地号段
     */
    private Long nextPosition(String ruleId, SeqRule rule, String groupKey, Long count) {
        CounterCapable counter = counterOf(rule);
        SpaceKey key = SpaceKey.of(space, counterKeyOf(ruleId, groupKey));
        if (rule.getSegment() <= 0) {
            long position = counter.incrementAndGet(key, 1, 0);
            if (count != null && !rule.isRecycle() && position > count) {
                return null;
            }
            return position;
        }
        return segmentOf(rule, ruleId, groupKey, counter, key).next(count, rule.isRecycle());
    }

    /**
     * 号段实例缓存：键含规则内容指纹，规则热更新后旧实例自然废弃，由 LRU 淘汰。
     * 创建走全局锁串行化（仅首次访问竞争），保证同一计数键同一时刻仅一个号段实例
     */
    private LocalSegment segmentOf(SeqRule rule, String ruleId, String groupKey,
                                   CounterCapable counter, SpaceKey key) {
        String cacheKey = ruleId + "@" + groupKey + "@" + Integer.toHexString(rule.hashCode());
        LocalSegment segment = segments.get(cacheKey);
        if (segment != null) {
            return segment;
        }
        synchronized (segmentLock) {
            segment = segments.get(cacheKey);
            if (segment == null) {
                segment = new LocalSegment(counter, key, rule.getSegment());
                segments.put(cacheKey, segment);
            }
            return segment;
        }
    }

    /**
     * 计数位置 → 序号值：耗尽已在取号层拒绝，此处仅处理循环取模
     */
    private long valueOf(SeqRule rule, Long count, long position) {
        long pos = position - 1;
        if (count != null && pos >= count) {
            pos = pos % count;
        }
        return rule.getStart() + pos * rule.getStep();
    }

    // ------------------------------------------------- 规则与校验

    private SeqRule ruleOf(String ruleId) {
        SeqRule rule = rules.get(ruleId);
        if (rule == null) {
            throw new SeqConfigException("Sequence rule not found|name=" + ruleId);
        }
        return rule;
    }

    /**
     * 规则解析与校验（配置驱动注册表工厂，热更新失败保旧实例）
     */
    static SeqRule parseRule(String json) {
        SeqRule rule;
        try {
            rule = JsonUtil.toBean(json, SeqRule.class);
        } catch (RuntimeException e) {
            throw new SeqConfigException("Invalid sequence rule json|json=" + json, e);
        }
        if (rule == null) {
            throw new SeqConfigException("Empty sequence rule json|json=" + json);
        }
        if (rule.getStep() < 1) {
            throw new SeqConfigException("Sequence rule step must be >= 1|step=" + rule.getStep());
        }
        if (rule.getSegment() < 0) {
            throw new SeqConfigException("Sequence rule segment must be >= 0|segment=" + rule.getSegment());
        }
        if (rule.getMaxValue() != null && rule.getMaxValue() < rule.getStart()) {
            throw new SeqConfigException("Sequence rule maxValue must be >= start"
                    + "|start=" + rule.getStart() + "|maxValue=" + rule.getMaxValue());
        }
        return rule;
    }

    /**
     * 可用序号个数（maxValue 含端点）；不设上限返回 null 表示无限
     */
    private Long availableCount(String ruleId, SeqRule rule) {
        if (rule.getMaxValue() == null) {
            return null;
        }
        long count = (rule.getMaxValue() - rule.getStart()) / rule.getStep() + 1;
        if (count <= 0) {
            throw new SeqConfigException("Sequence rule maxValue must be >= start|name=" + ruleId);
        }
        return count;
    }

    private CounterCapable counterOf(SeqRule rule) {
        KvStore store = resolveStore(rule.getStore());
        CounterCapable counter = KvStores.capabilityOf(store, CounterCapable.class);
        if (counter == null) {
            throw new SeqConfigException("Store not counter capable|store=" + store.getClass().getName());
        }
        return counter;
    }

    private KvStore resolveStore(String storeName) {
        if (storeName == null || storeName.trim().isEmpty()) {
            return defaultStore;
        }
        try {
            return SeqStores.global().resolve(storeName);
        } catch (IllegalArgumentException e) {
            throw new SeqConfigException("Seq store not registered|store=" + storeName, e);
        }
    }

    // ------------------------------------------------- 分组与键

    private String groupKeyOf(String ruleId, SeqRule rule, Map<String, Object> ext) {
        if (rule.getGroup() == null) {
            return "";
        }
        GroupKeyPolicy policy = groupPolicies.get(rule.getGroup().getType())
                .orElseThrow(() -> new SeqConfigException("Group policy not found"
                        + "|name=" + ruleId + "|type=" + rule.getGroup().getType()));
        String groupKey;
        try {
            groupKey = policy.groupKey(new GroupKeyPolicy.Context(
                    ruleId, rule.getGroup(),
                    ext == null ? Collections.emptyMap() : ext,
                    clock));
        } catch (SeqConfigException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SeqConfigException("Group key failed|name=" + ruleId
                    + "|type=" + rule.getGroup().getType(), e);
        }
        if (groupKey == null || groupKey.trim().isEmpty()) {
            return "";
        }
        if (groupKey.indexOf(':') >= 0) {
            throw new SeqConfigException("Group key must not contain ':'|groupKey=" + groupKey);
        }
        return groupKey;
    }

    private static String counterKeyOf(String ruleId, String groupKey) {
        return groupKey.isEmpty() ? ruleId : ruleId + "." + groupKey;
    }

    private static String nameOf(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new SeqConfigException("Sequence name must not be empty");
        }
        if (name.indexOf(':') >= 0) {
            throw new SeqConfigException("Sequence name must not contain ':'|name=" + name);
        }
        return name;
    }

    // ------------------------------------------------- 格式化

    private String format(Issued issued) {
        SeqRule rule = issued.rule;
        String seq = pad(rule.getSeqLength(), issued.value);
        if (rule.getFormat() == null || rule.getFormat().trim().isEmpty()) {
            return seq;
        }
        Map<String, Object> context = new HashMap<>();
        context.put("name", issued.ruleId);
        context.put("group", issued.groupKey);
        context.put("seq", seq);
        return templates.computeIfAbsent(rule.getFormat(), TextTemplate::new).render(context);
    }

    private static String pad(int length, long value) {
        String s = Long.toString(value);
        if (length <= 0 || s.length() >= length) {
            return s;
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = s.length(); i < length; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    /**
     * 一次取号的结果（供格式化复用分组标识与规则）
     */
    private static class Issued {

        private final String ruleId;
        private final String groupKey;
        private final SeqRule rule;
        private final Long value;

        private Issued(String ruleId, String groupKey, SeqRule rule, Long value) {
            this.ruleId = ruleId;
            this.groupKey = groupKey;
            this.rule = rule;
            this.value = value;
        }
    }
}
