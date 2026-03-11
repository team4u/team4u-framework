package com.team4u.framework.retry;

import com.team4u.framework.retry.util.RetryExceptionUtil;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.ExecutionException;

public class RetryExceptionUtilTest {

    @Test
    public void testUnwrapNormal() {
        RuntimeException root = new RuntimeException("root");
        ExecutionException wrapper = new ExecutionException(root);

        Throwable result = RetryExceptionUtil.unwrap(wrapper);
        Assert.assertEquals(root, result);
    }

    @Test
    public void testUnwrapInfiniteLoop() {
        // 创建循环引用：a -> b -> a
        CustomException a = new CustomException("a");
        CustomException b = new CustomException("b");
        a.setCause(b);
        b.setCause(a);

        // unwrap 应该在达到最大深度后停止，而不是死循环
        Throwable result = RetryExceptionUtil.unwrap(a);
        Assert.assertNotNull(result);
    }

    @Test
    public void testUnwrapDeepChain() {
        // 创建超过最大深度的异常链
        Throwable current = new RuntimeException("root");
        for (int i = 0; i < 20; i++) {
            current = new ExecutionException(current);
        }

        // unwrap 应该在达到最大深度后停止
        Throwable result = RetryExceptionUtil.unwrap(current);
        Assert.assertNotNull(result);
        Assert.assertTrue(result instanceof ExecutionException);
    }

    @Test
    public void testUnwrapAndRestoreInterruptPreservesFlag() {
        try {
            Throwable result = RetryExceptionUtil.unwrapAndRestoreInterrupt(
                    new ExecutionException(new InterruptedException("stop")));
            Assert.assertTrue(result instanceof InterruptedException);
            Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static class CustomException extends ExecutionException {
        private Throwable cause;

        public CustomException(String message) {
            super(message, null);
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }

        public void setCause(Throwable cause) {
            this.cause = cause;
        }
    }
}
