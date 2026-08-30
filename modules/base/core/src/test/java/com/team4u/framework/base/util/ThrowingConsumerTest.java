package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ThrowingConsumer 单元测试
 *
 * @author jay.wu
 */
public class ThrowingConsumerTest {

    @Test
    public void testAccept() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        ThrowingConsumer<String> consumer = result::set;

        // 正常消费
        consumer.accept("test");
        Assert.assertEquals("消费值不正确", "test", result.get());
    }

    @Test(expected = IOException.class)
    public void testAcceptWithException() throws Exception {
        ThrowingConsumer<String> consumer = s -> {
            throw new IOException("测试异常");
        };

        // 验证受检异常抛出
        consumer.accept("error");
    }
}
