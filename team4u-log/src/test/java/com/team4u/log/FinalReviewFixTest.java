package com.team4u.log;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.log.appender.LogAppender;
import com.team4u.log.core.LogEngine;
import com.team4u.log.core.LogEvent;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终 Review 修复测试
 */
public class FinalReviewFixTest {

    private MockMemoryAppender mockAppender;
    private LogAppender originalAppender;
    private TestConfigContext testConfigContext;

    @Before
    public void setup() {
        LogEngine.getInstance().reset();

        originalAppender = LogEngine.getInstance().getAppender();
        mockAppender = new MockMemoryAppender();
        LogEngine.getInstance().setAppender(mockAppender);

        testConfigContext = TestConfigContext.create();
        LogBootstrap.start(testConfigContext.getManager());
    }

    @After
    public void teardown() {
        LogEngine.getInstance().setAppender(originalAppender);
        testConfigContext.destroy();
    }

    /**
     * 验证：即便原生级别被禁用，如果有染色规则，依然能够跨过 Loggers 的保护进入流水线。
     */
    @Test
    public void testDyeingEvenIfLevelDisabled() {
        // 假设当前 Logger 的 DEBUG 级别是开启的（默认通常是这样），我们要模拟一个被禁用的场景。
        // 由于难以动态修改 SLF4J 的级别设置（除非引入 Logback 依赖并强转），
        // 我们利用 TargetedDyeingInterceptor 是否有规则作为探针。

        // 1. 无规则时，尝试追踪级别（假设 TRACE 是禁用的）
        Loggers.of(this.getClass()).level(Level.TRACE).action("NoRule").log();
        // 如果 TRACE 禁用，mockAppender 应该是空的
        // 注意：在标准单元测试环境下，TRACE 通常是禁用的。
        int initialSize = mockAppender.capturedEvents.size();

        // 2. 推送一条染色规则，将满足条件的日志提权到 INFO
        String config = "{" +
                "  \"dyeingRules\": [" +
                "    { \"id\": \"trace_to_info\", \"condition\": \"action == 'DyeMe'\", \"targetLevel\": \"INFO\" }" +
                "  ]" +
                "}";
        testConfigContext.put("team4u.log.config", config);

        // 3. 再次以 TRACE 级别打印，但命中染色规则
        Loggers.of(this.getClass()).level(Level.TRACE).action("DyeMe").log();

        // 验证：虽然原始级别是 TRACE，但由于有染色规则，成功进入流水线并被提权
        Assert.assertEquals("染色应当让日志跨过保护过滤", initialSize + 1, mockAppender.capturedEvents.size());
        LogEvent event = mockAppender.lastEvent();
        Assert.assertEquals("级别应当被提升至 INFO", Level.INFO, event.getLevel());
        Assert.assertEquals("dyeingRuleMatched 标记应存在", "trace_to_info", event.getPayload().get("dyeingRuleMatched"));
    }

    private static class MockMemoryAppender implements LogAppender {
        private final List<LogEvent> capturedEvents = new ArrayList<>();

        @Override
        public void append(LogEvent event) {
            capturedEvents.add(event);
        }

        public LogEvent lastEvent() {
            if (capturedEvents.isEmpty()) return null;
            return capturedEvents.get(capturedEvents.size() - 1);
        }
    }
}
