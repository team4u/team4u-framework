package com.team4u.framework.retry.managed.recovery;

import org.junit.Assert;
import org.junit.Test;

public class RecoveryExecutionContextTest {

    @Test
    public void testRecoveryExecutionContextClearsThreadLocalWhenActionThrows() {
        Assert.assertFalse(RecoveryExecutionContext.isRecovering());

        try {
            RecoveryExecutionContext.run(() -> {
                Assert.assertTrue(RecoveryExecutionContext.isRecovering());
                throw new IllegalStateException("boom");
            });
            Assert.fail("expected IllegalStateException");
        } catch (Exception ex) {
            Assert.assertTrue(ex instanceof IllegalStateException);
            Assert.assertEquals("boom", ex.getMessage());
        }

        Assert.assertFalse(RecoveryExecutionContext.isRecovering());
    }
}
