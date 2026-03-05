package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证 Error（如 OutOfMemoryError）是否能干净地穿透拦截器，
 * 并且不会触发无意义的重试。
 */
public class RetryErrorTest {

    @Before
    public void setup() {
        RetryPolicyRegistry.global().unregisterAll();
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return "error-policy";
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder()
                        .totalAttempts(3)
                        .build();
            }
        });
    }

    @Test
    public void testErrorShouldNotRetryAndNotWrap() {
        AtomicInteger executeCount = new AtomicInteger(0);

        ErrorService errorService = new ErrorService() {
            @Override
            @Retryable("error-policy")
            public String throwError() {
                executeCount.incrementAndGet();
                throw new OutOfMemoryError("Fake OOM");
            }
        };

        ErrorService proxy = ProxyBuilder.forClass(ErrorService.class)
                .withDelegate(errorService)
                .addInterceptor(new RetryInterceptor())
                .build();

        try {
            proxy.throwError();
            Assert.fail("应该抛出 OutOfMemoryError");
        } catch (OutOfMemoryError e) {
            Assert.assertEquals("Fake OOM", e.getMessage());
        } catch (Throwable t) {
            Assert.fail("期望捕获 OutOfMemoryError，但实际捕获了: " + t.getClass().getName());
        }

        Assert.assertEquals("遇到 Error 时应该立刻阻断，不应重试", 1, executeCount.get());
    }

    public interface ErrorService {
        String throwError();
    }
}
