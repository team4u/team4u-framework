package com.team4u.framework.mask.config;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.support.AbstractJsonConfigRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏规则仓库
 * <p>
 * 维护第三方类或 Map 的脱敏规则，支持快速检索。
 * init/stop/解析/热更新骨架收编自 {@link AbstractJsonConfigRepository}，
 * 统一降级语义：首次加载失败抛异常，热更新失败保留旧配置。
 */
public class MaskRuleRepository extends AbstractJsonConfigRepository<Map<String, Map<String, String>>> {

    private static final MaskRuleRepository INSTANCE = new MaskRuleRepository();

    // 配置中心的 Key
    private static final String CONFIG_KEY = "team4u.mask.rules";

    /**
     * 手动注入规则缓存（主要用于测试场景，优先级低于配置中心）
     */
    private volatile Map<String, Map<String, String>> manualRuleCache = new HashMap<>();

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

    @Override
    protected Map<String, Map<String, String>> emptyConfig() {
        return new HashMap<>();
    }

    @Override
    protected void onConfigLoaded(Map<String, Map<String, String>> oldValue,
                                  Map<String, Map<String, String>> newValue) {
        // 配置中心加载成功后清空手动缓存，保证配置中心的优先级
        this.manualRuleCache = new HashMap<>();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        this.manualRuleCache = new HashMap<>();
    }

    /**
     * 重置仓库状态（用于测试环境隔离）
     * <p>
     * 清空规则缓存并释放与 ConfigManager 的监听关系，
     * 确保下次 init() 时从干净状态重新初始化。
     */
    public synchronized void reset() {
        stop();
    }

    /**
     * 直接注入规则缓存（测试专用，避免反射操作）
     *
     * @param rules className -> (fieldName -> maskPolicyKey)
     */
    public void setRuleCache(Map<String, Map<String, String>> rules) {
        this.manualRuleCache = rules != null ? rules : new HashMap<>();
    }

    /**
     * 检索脱敏规则
     *
     * @param className 类名
     * @param fieldName 字段名
     * @return 匹配到的规则 Key，若无则返回 null
     */
    public String findRule(String className, String fieldName) {
        Map<String, Map<String, String>> rules = currentRules();

        // 精确匹配具体的类名（优先级最高，允许特殊类覆盖全局规则）
        Map<String, String> classRules = rules.get(className);
        if (classRules != null) {
            String classRule = classRules.get(fieldName);
            if (classRule != null) {
                return classRule;
            }
        }

        // 兜底匹配：全局通配符规则（配置了 "*" 的字段）
        Map<String, String> globalRules = rules.get("*");
        if (globalRules != null) {
            return globalRules.get(fieldName);
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
            if (rules != null) {
                return rules;
            }
        }
        return manualRuleCache;
    }
}
