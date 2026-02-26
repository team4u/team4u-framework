package com.team4u.framework.proxy;

import org.junit.Assert;
import org.junit.Test;

/**
 * 代理示例测试类
 *
 * @author jay.wu
 */
public class ProxySampleTest {

    @Test
    public void testProxySample() {
        ProxySample sample = message -> "Hello, " + message;
        Assert.assertEquals("Hello, World", sample.execute("World"));
    }
}
