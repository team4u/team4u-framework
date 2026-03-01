package com.team4u.log.pipeline.interceptor;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.log.config.LogDynamicConfig.DyeingRule;
import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.LogInterceptor;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定向染色拦截器
 * <p>
 * 支持根据规则动态调整日志级别，基于 team4u-criterion 进行高效匹配。
 */
public class TargetedDyeingInterceptor implements LogInterceptor {

    private static final TargetedDyeingInterceptor INSTANCE = new TargetedDyeingInterceptor();

    private volatile List<DyeingRule> activeRules = new ArrayList<>();

    private TargetedDyeingInterceptor() {
        reset();
    }

    public static TargetedDyeingInterceptor getInstance() {
        return INSTANCE;
    }

    public void reset() {
        this.activeRules = new ArrayList<>();
    }

    /**
     * 判断当前是否存在有效的染色规则
     */
    public boolean hasActiveRules() {
        return activeRules != null && !activeRules.isEmpty();
    }

    /**
     * 刷新限流规则
     *
     * @param rules 染色规则列表
     */
    public void refreshRules(List<DyeingRule> rules) {
        if (rules == null) {
            this.activeRules = new ArrayList<>();
            return;
        }
        this.activeRules = rules;

        // 预编译表达式，提升首次匹配性能
        for (DyeingRule rule : rules) {
            try {
                Criteria.global().compileExpression(rule.getCondition());
            } catch (Exception e) {
                System.err.println("[Team4u-Log] Failed to compile dyeing rule condition: " + rule.getId());
            }
        }
    }

    @Override
    public int priority() {
        return NORMAL; // 在 MDC 数据填充之后执行
    }

    @Override
    public boolean handle(LogEvent event) {
        List<DyeingRule> rules = activeRules;
        if (rules == null || rules.isEmpty()) {
            return true;
        }

        // 构建匹配上下文
        Map<String, Object> ctxMap = new HashMap<>(event.getPayload());
        ctxMap.put("action", event.getAction());
        ctxMap.put("level", event.getLevel() != null ? event.getLevel().name() : "UNKNOWN");

        String userId = MDC.get("X-User-Id");
        if (userId != null) {
            ctxMap.put("userId", userId);
        }

        MatchContext matchContext = MatchContext.of(ctxMap);

        // 逐条匹配规则
        for (DyeingRule rule : rules) {
            try {
                if (Criteria.global().matches(rule.getCondition(), matchContext)) {
                    // 命中染色规则，调整日志级别
                    event.setLevel(rule.getTargetLevel());
                    event.getPayload().put("dyeingRuleMatched", rule.getId());
                    break; 
                }
            } catch (Exception e) {
                // 安全隔离：单条规则匹配错误不影响整体流程
                System.err.println("[Team4u-Log] TargetedDyeing Error: " + rule.getId());
            }
        }

        return true;
    }
}
