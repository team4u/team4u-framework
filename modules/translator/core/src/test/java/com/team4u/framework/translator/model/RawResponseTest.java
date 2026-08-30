package com.team4u.framework.translator.model;

import org.junit.Test;

/**
 * RawResponse 领域模型单元测试
 */
public class RawResponseTest {

    /**
     * 测试构建实体时校验异常实例不可为 null
     */
    @Test(expected = NullPointerException.class)
    public void testOfWithNullCause() {
        RawResponse.of("SYS", null);
    }
}
