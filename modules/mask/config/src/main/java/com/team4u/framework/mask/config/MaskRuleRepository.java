package com.team4u.framework.mask.config;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.AbstractJsonConfigRepository;
import com.team4u.framework.mask.MaskRuleResolver;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏规则仓库
 * <p>
 * 维护第三方类或 Map 的脱敏规则，支持快速检索。
 * init/stop/解析/热更新骨架收编自 {@link AbstractJsonConfigRepository}，
 * 统一降级语义：首次加载失败抛异常，热更新失败保留旧配置。
 * <p>
 * 安全语义（保持既有契约）：
 * <ul>
 *     <li>规则快照不可变（深拷贝 + unmodifiable），注册后外部修改不影响仓库；</li>
 *     <li>解析与手动注入统一走 {@link #validateRules}——null 类规则 / null 规则值
 *     快速失败，检索期对已存在键的 null 值同样抛出（fail-closed，不静默放行）；</li>
 *     <li>实现 {@link MaskRuleResolver}，经
 *     {@link MaskRuleResolver.Global} 按 init/reset 生命周期安装/注销，
 *     CAS 卸载避免误删他人安装的解析器。</li>
 * </ul>
 */
public class MaskRuleRepository extends AbstractJsonConfigRepository<Map<String, Map<String, String>>>
        implements MaskRuleResolver {

    private static final MaskRuleRepository INSTANCE = new MaskRuleRepository();

    // 配置中心的 Key
    private static final String CONFIG_KEY = "team4u.mask.rules";

    /**
     * 手动注入规则缓存（主要用于测试场景，优先级低于配置中心）
     */
    private volatile Map<String, Map<String, String>> manualRuleCache = Collections.emptyMap();

    private MaskRuleRepository() {
    }

    /**
     * 获取规则仓库单例实例
     *
     * @return MaskRuleRepository 实例
     */
    public static MaskRuleRepository getInstance() {
        return INSTANCE;
    }

    @Override
    protected String configKey() {
        return CONFIG_KEY;
    }

    @Override
    protected TypeReference<Map<String, Map<String, String>>> typeReference() {
        return new TypeReference<Map<String, Map<String, String>>>() {
        };
    }

    /**
     * 解析并校验规则：保持既有的「非法配置 → IllegalArgumentException」快速失败语义。
     */
    @Override
    protected Map<String, Map<String, String>> parseJson(String json) throws Exception {
        Map<String, Map<String, String>> rules;
        try {
            rules = super.parseJson(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid mask rule config", e);
        }
        validateRules(rules);
        return immutableSnapshot(rules);
    }

    @Override
    protected Map<String, Map<String, String>> emptyConfig() {
        return new HashMap<>();
    }

    @Override
    protected void onConfigLoaded(Map<String, Map<String, String>> oldValue,
                                  Map<String, Map<String, String>> newValue) {
        // 配置中心加载成功后清空手动缓存，保证配置中心的优先级
        this.manualRuleCache = Collections.emptyMap();
    }

    /**
     * 初始化仓库并安装全局规则解析器
     */
    @Override
    public synchronized void init(ConfigManager configManager) {
        super.init(configManager);
        MaskRuleResolver.Global.install(this);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        this.manualRuleCache = Collections.emptyMap();
    }

    /**
     * 重置仓库状态（用于测试环境隔离）
     * <p>
     * 清空规则缓存并释放与 ConfigManager 的监听关系，
     * 确保下次 init() 时从干净状态重新初始化。
     */
    public synchronized void reset() {
        stop();
        MaskRuleResolver.Global.uninstall(this);
    }

    /**
     * 直接注入规则缓存（测试专用，避免反射操作）
     *
     * @param rules className -> (fieldName -> maskPolicyKey)
     */
    public void setRuleCache(Map<String, Map<String, String>> rules) {
        this.manualRuleCache = immutableSnapshot(rules);
    }

    /**
     * 检索脱敏规则
     *
     * @param className 类名
     * @param fieldName 字段名
     * @return 匹配到的规则 Key，若无则返回 null
     */
    @Override
    public String findRule(String className, String fieldName) {
        Map<String, Map<String, String>> rules = currentRules();

        // 精确匹配具体的类名（优先级最高，允许特殊类覆盖全局规则）
        Map<String, String> classRules = rules.get(className);
        if (rules.containsKey(className)) {
            if (classRules == null) {
                throw new IllegalArgumentException("Mask class rules must not be null: "
                        + className);
            }
            if (classRules.containsKey(fieldName)) {
                String classRule = classRules.get(fieldName);
                if (classRule == null) {
                    throw new IllegalArgumentException("Mask rule must not be null: "
                            + className + "." + fieldName);
                }
                return classRule;
            }
        }

        // 兜底匹配：全局通配符规则（配置了 "*" 的字段）
        Map<String, String> globalRules = rules.get("*");
        if (rules.containsKey("*") && globalRules == null) {
            throw new IllegalArgumentException("Mask class rules must not be null: *");
        }
        if (globalRules != null && globalRules.containsKey(fieldName)) {
            String globalRule = globalRules.get(fieldName);
            if (globalRule == null) {
                throw new IllegalArgumentException("Mask rule must not be null: *." + fieldName);
            }
            return globalRule;
        }

        return null;
    }

    /**
     * 获取指定类的脱敏规则
     *
     * @param className 类名
     * @return 规则 Map
     */
    public Map<String, String> getClassRules(String className) {
        return currentRules().get(className);
    }

    private Map<String, Map<String, String>> currentRules() {
        if (isInitialized()) {
            Map<String, Map<String, String>> rules = get();
            if (rules != null && !rules.isEmpty()) {
                return rules;
            }
        }
        return manualRuleCache;
    }

    private static Map<String, Map<String, String>> immutableSnapshot(
            Map<String, Map<String, String>> rules) {
        if (rules == null) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, String>> snapshot = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> classEntry : rules.entrySet()) {
            Map<String, String> classRules = classEntry.getValue();
            if (classRules != null) {
                classRules = Collections.unmodifiableMap(new HashMap<>(classRules));
            }
            snapshot.put(classEntry.getKey(), classRules);
        }

        validateRules(snapshot);
        return Collections.unmodifiableMap(snapshot);
    }

    private static void validateRules(Map<String, Map<String, String>> rules) {
        for (Map.Entry<String, Map<String, String>> classEntry : rules.entrySet()) {
            Map<String, String> classRules = classEntry.getValue();
            if (classRules == null) {
                throw new IllegalArgumentException("Mask class rules must not be null: "
                        + classEntry.getKey());
            }
            for (Map.Entry<String, String> fieldEntry : classRules.entrySet()) {
                if (fieldEntry.getValue() == null) {
                    throw new IllegalArgumentException("Mask rule must not be null: "
                            + classEntry.getKey() + "." + fieldEntry.getKey());
                }
            }
        }
    }
}
