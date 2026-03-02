package com.team4u.log.pipeline.interceptor;

import com.team4u.log.LogContext;
import com.team4u.log.config.LogDynamicConfig;
import com.team4u.log.core.LogEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.util.Collections;

/**
 * 定向染色拦截器单元测试
 */
public class TargetedDyeingInterceptorTest {

    private TargetedDyeingInterceptor interceptor;

    @Before
    public void setup() {
        interceptor = TargetedDyeingInterceptor.getInstance();
        interceptor.reset();
        MDC.clear();
    }

    @Test
    public void testDyeingByAction() {
        // 1. 配置规则：action 为 'DyeMe' 则染色为 DEBUG
        LogDynamicConfig.DyeingRule rule = new LogDynamicConfig.DyeingRule();
        rule.setId("rule1");
        rule.setCondition("action == 'DyeMe'");
        rule.setTargetLevel(Level.DEBUG);
        interceptor.refreshRules(Collections.singletonList(rule));

        // 2. 执行染色
        LogEvent event = new LogEvent().setAction("DyeMe").setLevel(Level.INFO);
        interceptor.handle(event);

        // 3. 验证结果
        Assert.assertEquals(Level.DEBUG, event.getLevel());
        Assert.assertEquals("rule1", event.getPayload().get("dyeingRuleMatched"));
    }

    @Test
    public void testNoMatch() {
        LogDynamicConfig.DyeingRule rule = new LogDynamicConfig.DyeingRule();
        rule.setCondition("action == 'Special'");
        rule.setTargetLevel(Level.WARN);
        interceptor.refreshRules(Collections.singletonList(rule));

        LogEvent event = new LogEvent().setAction("Normal").setLevel(Level.INFO);
        interceptor.handle(event);

        Assert.assertEquals(Level.INFO, event.getLevel());
    }

    @Test
    public void testDyeingByFullMdc() {
        // 1. 配置规则：验证全量 MDC 注入（直接使用原始 key）
        LogDynamicConfig.DyeingRule rule = new LogDynamicConfig.DyeingRule();
        rule.setId("rule-full-mdc");
        rule.setCondition("traceId == 'T123' && cluster == 'gray'");
        rule.setTargetLevel(Level.TRACE);
        interceptor.refreshRules(Collections.singletonList(rule));

        // 2. 模拟 MDC
        MDC.put("traceId", "T123");
        MDC.put("cluster", "gray");

        LogEvent event = new LogEvent().setAction("Test").setLevel(Level.INFO);
        interceptor.handle(event);

        // 3. 验证结果
        Assert.assertEquals(Level.TRACE, event.getLevel());
        Assert.assertEquals("rule-full-mdc", event.getPayload().get("dyeingRuleMatched"));
    }

    @Test
    public void testDyeingByCustomSource() {
        // 1. 配置规则：引用自定义注入的变量
        LogDynamicConfig.DyeingRule rule = new LogDynamicConfig.DyeingRule();
        rule.setId("rule-custom");
        rule.setCondition("customAttr == 'V1'");
        rule.setTargetLevel(Level.DEBUG);
        interceptor.refreshRules(Collections.singletonList(rule));

        // 2. 注册自定义寻值源 (使用全局静态入口)
        LogContext.addSource((event, key) -> "customAttr".equals(key) ? "V1" : null);

        LogEvent event = new LogEvent().setAction("Test").setLevel(Level.INFO);
        interceptor.handle(event);

        // 3. 验证结果
        Assert.assertEquals(Level.DEBUG, event.getLevel());
    }

    @Test
    public void testPriority() {
        Assert.assertEquals(0, interceptor.priority());
    }

    @Test
    public void testInvalidExpression() {
        LogDynamicConfig.DyeingRule rule = new LogDynamicConfig.DyeingRule();
        rule.setId("invalid");
        rule.setCondition("!!! invalid syntax !!!");
        rule.setTargetLevel(Level.WARN);

        // 刷新规则不应抛出异常（安全隔离）
        interceptor.refreshRules(Collections.singletonList(rule));

        LogEvent event = new LogEvent().setAction("Any").setLevel(Level.INFO);
        interceptor.handle(event);
        Assert.assertEquals(Level.INFO, event.getLevel());
    }
}
