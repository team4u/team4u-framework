package com.team4u.framework.log.pipeline.interceptor;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.log.LogContext;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.LogInterceptor;
import com.team4u.framework.log.pipeline.context.LogContextCollector;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 定向染色拦截器
 * <p>
 * 支持根据规则动态调整日志级别，基于 team4u-criterion 进行高效匹配。
 * 通过 LogContext 全局收集上下文信息。
 * <p>
 * 内嵌的配置仓库骨架（init/stop/解析/热更新）已收编为
 * {@link com.team4u.framework.config.core.support.AbstractJsonConfigRepository}
 * 的私有内部类 {@link RuleRepository}，本类只保留拦截匹配逻辑。
 * 统一降级语义：首次加载失败抛异常，热更新失败保留旧规则。
 */
public class TargetedDyeingInterceptor implements LogInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TargetedDyeingInterceptor.class);

    private static final TargetedDyeingInterceptor INSTANCE = new TargetedDyeingInterceptor();

    /**
     * 统一管理染色规则的配置仓库（继承模板骨架）
     */
    private final RuleRepository ruleRepository = new RuleRepository();

    /**
     * 当前生效的染色规则快照（volatile 保证热更新后立即可见）
     */
    private volatile List<DyeingRule> activeRules = Collections.emptyList();

    private volatile Criteria criteria = Criteria.global();

    private TargetedDyeingInterceptor() {
        this.stop();
    }

    /**
     * 获取定向染色拦截器单例实例
     *
     * @return TargetedDyeingInterceptor 实例
     */
    public static TargetedDyeingInterceptor getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化染色规则配置并挂载监听
     *
     * @param configManager 配置管理器
     */
    public synchronized void init(ConfigManager configManager) {
        this.ruleRepository.init(configManager);
    }

    /**
     * 停止拦截器：注销配置监听、清空规则并重置全局日志上下文
     * <p>
     * 同时实现 {@link LogInterceptor#stop()} 的重置语义，供拦截器链统一重置。
     */
    @Override
    public synchronized void stop() {
        this.ruleRepository.stop();
        this.criteria = Criteria.global();
        // 同步重置全局日志上下文
        LogContext.reset();
    }

    public void setCriteria(Criteria criteria) {
        this.criteria = criteria == null ? Criteria.global() : criteria;
    }

    /**
     * 判断当前是否存在有效的染色规则
     */
    public boolean hasActiveRules() {
        return !activeRules.isEmpty();
    }

    @Override
    public boolean shouldBypassLevelPrecheck(LogEvent event) {
        return hasActiveRules();
    }

    @Override
    public int priority() {
        return NORMAL; // 在数据填充后执行，确保能收集到完整的上下文
    }

    @Override
    public boolean handle(LogEvent event) {
        List<DyeingRule> rules = activeRules;
        if (rules.isEmpty()) {
            return true;
        }

        // 核心步骤：通过全局收集器聚合 Payload、MDC、全局属性及自定义插件
        LogContextCollector collector = LogContext.getCollector();
        Map<String, Object> ctxMap = collector.collect(event);
        MatchContext matchContext = MatchContext.of(ctxMap);

        // 逐条匹配规则
        Criteria activeCriteria = this.criteria;
        for (DyeingRule rule : rules) {
            try {
                if (activeCriteria.matches(rule.getCondition(), matchContext)) {
                    // 命中染色规则，调整日志级别
                    event.setLevel(rule.getTargetLevel());
                    // 在 Payload 中标记命中，方便追溯
                    event.getPayload().put("dyeingRuleMatched", rule.getId());
                    break;
                }
            } catch (Exception e) {
                // 安全隔离：单条规则匹配错误不影响整体流程
                log.error("TargetedDyeingInterceptor|match|error|ruleId={}|msg={}", rule.getId(), e.getMessage());
            }
        }

        return true;
    }

    /**
     * 染色规则仓库
     * <p>
     * 解析后预编译条件表达式并过滤语法错误的规则；
     * 成功后把有效规则写回拦截器的 activeRules 快照。
     */
    private class RuleRepository
            extends com.team4u.framework.config.core.support.AbstractJsonConfigRepository<List<DyeingRule>> {

        private static final String CONFIG_KEY = "team4u.log.dyeing";

        @Override
        protected String configKey() {
            return CONFIG_KEY;
        }

        @Override
        protected List<DyeingRule> parseJson(String json) throws Exception {
            List<DyeingRule> parsedRules = JsonUtil.toList(json, DyeingRule.class);
            List<DyeingRule> validRules = new ArrayList<>();
            Criteria activeCriteria = criteria;

            // 预编译表达式，提升首次匹配性能，并过滤掉语法错误的规则
            for (DyeingRule rule : parsedRules) {
                try {
                    if (rule.getCondition() != null && !rule.getCondition().trim().isEmpty()) {
                        activeCriteria.compileExpression(rule.getCondition());
                        validRules.add(rule);
                    }
                } catch (Exception e) {
                    // 预热失败，打印错误日志，该规则将不会生效
                    log.error("TargetedDyeingInterceptor|onConfigChanged|error|ruleId={}|condition={}|msg={}",
                            rule.getId(), rule.getCondition(), e.getMessage());
                }
            }
            return validRules;
        }

        @Override
        protected List<DyeingRule> emptyConfig() {
            return Collections.emptyList();
        }

        @Override
        protected void onConfigLoaded(List<DyeingRule> oldValue, List<DyeingRule> newValue) {
            TargetedDyeingInterceptor.this.activeRules = newValue;
        }
    }

    /**
     * 染色规则配置
     */
    @Data
    public static class DyeingRule {
        /**
         * 规则 ID
         */
        private String id;

        /**
         * 匹配条件表达式（基于 team4u-criterion）
         */
        private String condition;

        /**
         * 命中规则后的目标日志级别
         */
        private Level targetLevel;
    }
}
