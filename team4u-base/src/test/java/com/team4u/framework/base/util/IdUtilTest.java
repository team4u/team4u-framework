package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * IdUtil 单元测试
 *
 * @author jay.wu
 */
public class IdUtilTest {

    @Test
    public void simpleUUID() {
        String uuid = IdUtil.simpleUUID();
        // 验证长度是否为 32 位
        Assert.assertEquals("UUID 长度应为 32 位", 32, uuid.length());
        // 验证是否不包含连字符
        Assert.assertFalse("UUID 不应包含连字符", uuid.contains("-"));
    }
}
