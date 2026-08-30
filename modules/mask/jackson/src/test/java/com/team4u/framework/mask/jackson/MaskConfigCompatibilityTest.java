package com.team4u.framework.mask.jackson;

import org.junit.Assert;
import org.junit.Test;

public class MaskConfigCompatibilityTest {

    @Test
    public void maskConfigKeepsLombokDataAndChainedAccessors() throws Exception {
        MaskConfig config = new MaskConfig();

        Assert.assertSame(config, config.setMaxStringLength(12));
        Assert.assertEquals(12, config.getMaxStringLength());
        Assert.assertEquals(Boolean.TRUE, config.getClass()
                .getDeclaredMethod("canEqual", Object.class)
                .invoke(config, new MaskConfig()));

        MaskConfig same = new MaskConfig().setMaxStringLength(12);
        MaskConfig different = new MaskConfig().setMaxStringLength(20);

        Assert.assertEquals(config, same);
        Assert.assertEquals(config.hashCode(), same.hashCode());
        Assert.assertNotEquals(config, different);
        Assert.assertTrue(config.toString().contains("maxStringLength=12"));
    }
}
