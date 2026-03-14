package com.team4u.framework.translator.model;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 渲染管线上下文的隔离防护单元测试
 */
public class RenderContextTest {

    /**
     * 测试管线上下文能够安全隔离外部注入参数，避免恶意或意外修改
     */
    @Test
    public void testSnapshotArgsAndExposeUnmodifiableMap() {
        RawResponse source = RawResponse.of("SYS", "E001", "系统异常");
        ErrorDef def = new ErrorDef();
        def.setCode("NEW");
        def.setDefaultMsg("msg");

        Map<String, Object> args = new HashMap<>();
        args.put("traceId", "t-1");

        RenderContext context = new RenderContext(source, def, args);
        // 修改原对象
        args.put("traceId", "t-2");

        // 断言快照生效
        Assert.assertEquals("t-1", context.getArgs().get("traceId"));

        try {
            // 断言其内容不可修改
            context.getArgs().put("action", "query");
            Assert.fail("args should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            Assert.assertTrue(true);
        }
    }
}
