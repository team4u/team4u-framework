package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * CopyOptions 单元测试
 *
 * @author jay.wu
 */
public class CopyOptionsTest {

    @Test
    public void testCreate() {
        CopyOptions options = CopyOptions.create();
        Assert.assertNotNull(options);
        Assert.assertFalse(options.isIgnoreCase());
        Assert.assertFalse(options.isIgnoreError());
    }

    @Test
    public void testIgnoreCase() {
        CopyOptions options = CopyOptions.create().ignoreCase();
        Assert.assertTrue(options.isIgnoreCase());
        Assert.assertFalse(options.isIgnoreError());
    }

    @Test
    public void testIgnoreError() {
        CopyOptions options = CopyOptions.create().ignoreError();
        Assert.assertTrue(options.isIgnoreError());
        Assert.assertFalse(options.isIgnoreCase());
    }

    @Test
    public void testBoth() {
        CopyOptions options = CopyOptions.create().ignoreCase().ignoreError();
        Assert.assertTrue(options.isIgnoreCase());
        Assert.assertTrue(options.isIgnoreError());
    }
}
