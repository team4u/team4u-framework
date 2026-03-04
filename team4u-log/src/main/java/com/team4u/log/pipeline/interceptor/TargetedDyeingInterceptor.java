package com.team4u.log.pipeline.interceptor;

import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.log.LogContext;
import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.LogInterceptor;
import com.team4u.log.pipeline.context.LogContextCollector;
import lombok.Data;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 定向染色拦截器
 * <p>
 * 支持根据规则动态调整日志级别，基于 team4u-criterion 进行高效匹配。
 * 通过 LogContext 全局收集上下文信息。
 */
public class TargetedDyeingInterceptor implements LogInterceptor {

    private static final Log log = Log.get();

    private static final TargetedDyeingInterceptor INSTANCE = new TargetedDyeingInterceptor();
    private static final String CONFIG_KEY = "team4u.log.dyeing";

    private volatile List<DyeingRule> activeRules = new ArrayList<>();

    private volatile Criteria criteria = Criteria.global();

    // 自我管理的 Registry
    private ConfigDrivenRegistry<List<DyeingRule>> registry;

    private TargetedDyeingInterceptor() {
        reset();
    }

    /**
     * 获取定向染色拦截器单例实例
     *
     * @return TargetedDyeingInterceptor 实例
     */
    public static TargetedDyeingInterceptor getInstance() {
        return INSTANCE;
    }

    public void init(ConfigManager configManager) {
        this.registry = new ConfigDrivenRegistry<>(configManager, CONFIG_KEY, json -> {
            try {
                if (json == null || json.trim().isEmpty()) {
                    this.activeRules = new ArrayList<>();
                    return this.activeRules;
                }

                List<DyeingRule> parsedRules = JSONUtil.toList(json, DyeingRule.class);
                List<DyeingRule> validRules = new ArrayList<>();
                Criteria activeCriteria = this.criteria;

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

                this.activeRules = validRules;
                return this.activeRules;
            } catch (Exception e) {
                log.error("TargetedDyeingInterceptor|parseConfig|error|msg={}", e.getMessage());
                return this.activeRules;
            }
        });

        this.registry.get(CONFIG_KEY);
    }

    @Override
    public void reset() {
        this.activeRules = new ArrayList<>();
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
