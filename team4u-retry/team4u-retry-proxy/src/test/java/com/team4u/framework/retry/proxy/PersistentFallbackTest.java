package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.retry.RetryHandoffException;
import com.team4u.framework.retry.RetryPolicy;
import com.team4u.framework.retry.TestLeaseBackend;
import com.team4u.framework.retry.policy.NamedRetryPolicy;
import com.team4u.framework.retry.policy.RetryPolicyRegistry;
import com.team4u.framework.retry.recovery.RetryTaskTypes;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久化降级单元测试
 *
 * @author jay.wu
 */
public class PersistentFallbackTest {

    @Test
    public void testFallbackToBackend() throws Throwable {
        // 1. 设置静态策略：全局最多 3 次，前台仅 1 次，随后交给后端
        String policyId = "fallback-test";
        RetryPolicyRegistry.global().register(new NamedRetryPolicy() {
            @Override
            public String key() {
                return policyId;
            }

            @Override
            public RetryPolicy getPolicy() {
                return RetryPolicy.builder().maxAttempts(3).localAttempts(1).build();
            }
        });

        // 2. 模拟 Backend
        AtomicInteger submitCount = new AtomicInteger(0);
        TestLeaseBackend mockBackend = new TestLeaseBackend() {
            @Override
            public void prepare(com.team4u.framework.retry.backend.RetryTaskSnapshot snapshot) {
                Assert.assertEquals(RetryTaskTypes.DEFAULT_PROXY_RECOVERY, snapshot.getTaskType());
                String contextJson = cn.hutool.json.JSONUtil.toJsonStr(snapshot);
                Assert.assertTrue("Context snippet should contain method name", contextJson.contains("doSomething"));
                Assert.assertTrue("Context snippet should contain arg value", contextJson.contains("test-arg"));
                snapshot.setTaskId("intent");
            }

            @Override
            public void complete(String taskId) {
            }

            @Override
            public void handoff(String taskId, long delayMillis) {
                submitCount.incrementAndGet();
                Assert.assertEquals("intent", taskId);
                Assert.assertEquals(1000, delayMillis);
            }
        };

        // 3. 创建增强拦截器的代理对象
        RetryInterceptor interceptor = new RetryInterceptor(mockBackend);
        TestServiceImpl delegate = new TestServiceImpl();
        TestService service = ProxyBuilder.forClass(TestService.class)
                .withDelegate(delegate)
                .addInterceptor(interceptor)
                .build();

        // 4. 执行业务方法（由于 persistent=true，重试耗尽后应抛出 RetryHandoffException 告知任务已被后台接管）
        try {
            service.doSomething("test-arg");
            Assert.fail("预期抛出 RetryHandoffException");
        } catch (RetryHandoffException e) {
            // success
        }

        Assert.assertEquals("预期已触发持久化降级提交", 1, submitCount.get());
    }

    public interface TestService {
        @Retryable(policy = "fallback-test")
        void doSomething(String arg);
    }

    public static class TestServiceImpl implements TestService {
        @Override
        public void doSomething(String arg) {
            throw new RuntimeException("Memory failure");
        }
    }
}
