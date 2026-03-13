package com.team4u.framework.retry.spring;

import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.client.ManagedRetryClient;
import com.team4u.framework.retry.common.concurrent.RetryExecutorManager;
import com.team4u.framework.retry.api.ManagedSubmitResult;
import com.team4u.framework.retry.managed.submit.RetryTaskSpec;
import com.team4u.framework.retry.proxy.invocation.InvocationArgSnapshot;
import com.team4u.framework.retry.proxy.invocation.InvocationRecoveryData;
import com.team4u.framework.retry.managed.model.RetryStatus;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.api.NamedRetryPolicyFactory;
import com.team4u.framework.retry.api.NamedRetryPolicyRegistry;
import com.team4u.framework.retry.proxy.InvocationReplay;
import com.team4u.framework.retry.proxy.RetryMode;
import com.team4u.framework.retry.proxy.Retryable;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.RecoveryHandler;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring 集成环境下的重试功能测试。
 * <p>
 * 涵盖了自动代理创建、注解识别（类级与方法级）、JDK 代理支持、
 * 托管重试模式约束校验以及 Spring 生命周期集成等核心场景。
 */
public class RetrySpringTest {

    @Before
    public void setup() {
        // 环境初始化：注销所有注册中心信息并注册测试专用重试策略
        NamedRetryPolicyRegistry.global().unregisterAll();
        RecoveryHandlerRegistry.global().unregisterAll();
        NamedRetryPolicyRegistry.global().register(new NamedRetryPolicyFactory() {
            @Override
            public String key() {
                return "test-policy";
            }

            @Override
            public RetryPolicy create() {
                return RetryPolicy.builder()
                        .maxRetries(2)
                        .backoff(Backoffs.fixed(1))
                        .build();
            }
        });
    }

    /**
     * 测试 Spring 自动代理是否能正确拦截带有 @Retryable 注解的方法。
     */
    @Test
    public void testSpringAutoProxy() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TestConfig config = context.getBean(TestConfig.class);

            OrderService orderService = context.getBean(OrderService.class);
            Assert.assertNotSame(OrderServiceImpl.class, orderService.getClass());
            Assert.assertEquals("ok_A100", orderService.doRetry("A100"));
            Assert.assertEquals(3, config.orderService.count.get());

            UserService userService = context.getBean(UserService.class);
            Assert.assertNotSame(UserService.class, userService.getClass());
            Assert.assertEquals("hello_world", userService.hello("world"));
            Assert.assertEquals(3, config.userService.count.get());

            ClassAnnotatedService classAnnotatedService = context.getBean(ClassAnnotatedService.class);
            Assert.assertNotSame(ClassAnnotatedService.class, classAnnotatedService.getClass());
            Assert.assertEquals("class_level_C300", classAnnotatedService.call("C300"));
            Assert.assertEquals(3, config.classAnnotatedService.count.get());
        }
    }

    /**
     * 测试在 JDK 原生代理环境下，当接口未定义注解但实现类定义了注解时，能否正确解析。
     */
    @Test
    public void testSpringJdkProxyShouldFindAnnotationOnImplementationMethod() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                JdkProxyConfig.class)) {
            JdkProxyConfig config = context.getBean(JdkProxyConfig.class);
            ImplAnnotatedService service = context.getBean(ImplAnnotatedService.class);

            Assert.assertNotSame(ImplAnnotatedServiceImpl.class, service.getClass());
            Assert.assertEquals("impl_B200", service.call("B200"));
            Assert.assertEquals(3, config.implAnnotatedService.count.get());
        }
    }

    /**
     * 测试 @EnableRetry 能否自动注册默认的恢复处理器（如 InvocationReplay）。
     */
    @Test
    public void testEnableRetryShouldAutoRegisterDefaultRecoveryHandler() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            Assert.assertTrue(RecoveryHandlerRegistry.global()
                    .get(InvocationReplay.TASK_NAME).isPresent());
        }
    }

    @Test
    public void testEnableRetryShouldProvideContextScopedRetryExecutorManager() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            Assert.assertNotNull(context.getBean(RetryExecutorManager.class));
        }
    }

    @Test
    public void testEnableRetryCoexistsWithExistingAutoProxyCreator() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            RootBeanDefinition beanDefinition = new RootBeanDefinition(InfrastructureAdvisorAutoProxyCreator.class);
            beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            context.registerBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME, beanDefinition);
            context.register(TestConfig.class);
            context.refresh();
            Object creator = context.getBean(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME);
            Assert.assertEquals(InfrastructureAdvisorAutoProxyCreator.class, creator.getClass());

            OrderService orderService = context.getBean(OrderService.class);
            Assert.assertEquals("ok_A100", orderService.doRetry("A100"));
        }
    }

    /**
     * 测试托管模式（MANAGED）是否强制要求业务方法返回值为 void。
     */
    @Test
    public void testSpringManagedMethodRejectsNonVoidReturnType() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ManagedConfig.class)) {
            ManagedConfig config = context.getBean(ManagedConfig.class);
            ManagedService service = context.getBean(ManagedService.class);

            try {
                service.notifyPay("M100");
                Assert.fail("expected IllegalStateException");
            } catch (IllegalStateException ex) {
                Assert.assertTrue(ex.getMessage().contains("only supports void return types"));
            }

            Assert.assertEquals(0, config.managedClient.submitCount.get());
        }
    }

    @Test
    public void testSpringManagedMethodRejectsCompletableFutureReturnType() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ManagedAsyncConfig.class)) {
            ManagedAsyncConfig config = context.getBean(ManagedAsyncConfig.class);
            ManagedAsyncService service = context.getBean(ManagedAsyncService.class);

            try {
                service.notifyPay("M100-async");
                Assert.fail("expected IllegalStateException");
            } catch (IllegalStateException ex) {
                Assert.assertTrue(ex.getMessage().contains("only supports void return types"));
            }

            Assert.assertEquals(0, config.managedClient.submitCount.get());
        }
    }

    @Test
    public void testSpringManagedMethodRejectsCustomRecovery() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ManagedCustomRecoveryConfig.class)) {
            ManagedCustomRecoveryService service = context.getBean(ManagedCustomRecoveryService.class);

            try {
                service.notifyPay("M101");
                Assert.fail("expected IllegalStateException");
            } catch (IllegalStateException ex) {
                Assert.assertTrue(ex.getMessage().contains("only supports InvocationReplay"));
            }
        }
    }

    @Test
    public void testSpringManagedModeWithoutManagedClientFailsFast() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ManagedWithoutClientConfig.class)) {
            ManagedWithoutClientConfig config = context.getBean(ManagedWithoutClientConfig.class);
            ManagedVoidOnlyService service = context.getBean(ManagedVoidOnlyService.class);

            try {
                service.notifyPay("M102");
                Assert.fail("expected IllegalStateException");
            } catch (IllegalStateException ex) {
                Assert.assertTrue(ex.getMessage().contains("requires ManagedRetryClient"));
            }

            Assert.assertEquals(0, config.managedService.count.get());
        }
    }

    @Test
    public void testRetryExecutorManagerIsContextOwned() {
        AnnotationConfigApplicationContext first = new AnnotationConfigApplicationContext(TestConfig.class);
        AnnotationConfigApplicationContext second = new AnnotationConfigApplicationContext(TestConfig.class);
        try {
            RetryExecutorManager firstManager = first.getBean(RetryExecutorManager.class);
            RetryExecutorManager secondManager = second.getBean(RetryExecutorManager.class);

            Assert.assertNotSame(firstManager, secondManager);
            first.close();

            Assert.assertTrue(firstManager.getScheduler().isShutdown());
            Assert.assertFalse(secondManager.getScheduler().isShutdown());
        } finally {
            if (second.isActive()) {
                second.close();
            }
        }
    }

    @Test
    public void testClosingContextOwnedManagerDoesNotShutdownGlobalManager() {
        RetryExecutorManager globalManager = RetryExecutorManager.global();
        globalManager.reset();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            RetryExecutorManager contextManager = context.getBean(RetryExecutorManager.class);
            Assert.assertNotSame(globalManager, contextManager);
        }

        Assert.assertFalse(globalManager.getScheduler().isShutdown());
    }

    @Test
    public void testInvocationReplayUsesTargetBeanNameInSpringContext() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ReplayConfig.class)) {
            NamedReplayBean primary = (NamedReplayBean) context.getBean("primaryReplayBean");
            NamedReplayBean secondary = (NamedReplayBean) context.getBean("secondaryReplayBean");

            InvocationReplay replay = new InvocationReplay();
            replay.recover(JsonUtil.toJsonStr(InvocationRecoveryData.builder()
                            .targetTypeName(ReplayHandler.class.getName())
                            .targetBeanName("secondaryReplayBean")
                            .methodName("replay")
                            .args(Collections.singletonList(InvocationArgSnapshot.builder()
                                    .typeName(String.class.getName())
                                    .serializedValue("\"spring-order\"")
                                    .ignored(false)
                                    .build()))
                            .build()),
                    RecoveryContext.builder().taskId("task-replay").attempt(1).build());

            Assert.assertNull(primary.lastOrderId);
            Assert.assertEquals("spring-order", secondary.lastOrderId);
        }
    }

    public interface OrderService {
        @Retryable(policy = "test-policy")
        String doRetry(String id);
    }

    public interface ImplAnnotatedService {
        String call(String id);
    }

    public interface ManagedService {
        String notifyPay(String id);
    }

    public interface ManagedCustomRecoveryService {
        void notifyPay(String id);
    }

    public interface ManagedAsyncService {
        CompletableFuture<String> notifyPay(String id);
    }

    public interface ManagedVoidOnlyService {
        void notifyPay(String id);
    }

    public interface ReplayHandler {
        void replay(String id);
    }

    /**
     * Spring 测试配置类，定义了各种类型的 Bean 以验证代理逻辑。
     */
    @Configuration
    @EnableRetry
    public static class TestConfig {
        private final OrderServiceImpl orderService = new OrderServiceImpl();
        private final UserService userService = new UserService();
        private final ClassAnnotatedService classAnnotatedService = new ClassAnnotatedService();

        @Bean
        public OrderService orderService() {
            return orderService;
        }

        @Bean
        public UserService userService() {
            return userService;
        }

        @Bean
        public ClassAnnotatedService classAnnotatedService() {
            return classAnnotatedService;
        }
    }

    public static class OrderServiceImpl implements OrderService {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public String doRetry(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "ok_" + id;
        }
    }

    @Service
    public static class UserService {
        private final AtomicInteger count = new AtomicInteger();

        @Retryable(policy = "test-policy")
        public String hello(String name) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "hello_" + name;
        }
    }

    @Retryable(policy = "test-policy")
    public static class ClassAnnotatedService {
        private final AtomicInteger count = new AtomicInteger();

        public String call(String value) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "class_level_" + value;
        }
    }

    public static class ImplAnnotatedServiceImpl implements ImplAnnotatedService {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        @Retryable(policy = "test-policy")
        public String call(String id) {
            if (count.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "impl_" + id;
        }
    }

    public static class ManagedServiceImpl implements ManagedService {
        @Override
        @Retryable(policy = "test-policy", mode = RetryMode.MANAGED)
        public String notifyPay(String id) {
            return "managed_" + id;
        }
    }

    public static class ManagedCustomRecoveryServiceImpl implements ManagedCustomRecoveryService {
        @Override
        @Retryable(policy = "test-policy", mode = RetryMode.MANAGED, recovery = CustomRecoveryHandler.class)
        public void notifyPay(String id) {
        }
    }

    public static class ManagedAsyncServiceImpl implements ManagedAsyncService {
        @Override
        @Retryable(policy = "test-policy", mode = RetryMode.MANAGED)
        public CompletableFuture<String> notifyPay(String id) {
            return CompletableFuture.completedFuture("managed_" + id);
        }
    }

    public static class ManagedVoidOnlyServiceImpl implements ManagedVoidOnlyService {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        @Retryable(policy = "test-policy", mode = RetryMode.MANAGED)
        public void notifyPay(String id) {
            count.incrementAndGet();
        }
    }

    public static class NamedReplayBean implements ReplayHandler {
        private String lastOrderId;

        @Override
        public void replay(String id) {
            this.lastOrderId = id;
        }
    }

    @Configuration
    @EnableRetry
    public static class JdkProxyConfig {
        private final ImplAnnotatedServiceImpl implAnnotatedService = new ImplAnnotatedServiceImpl();

        @Bean
        public ImplAnnotatedService implAnnotatedService() {
            return implAnnotatedService;
        }
    }

    @Configuration
    @EnableRetry
    public static class ManagedConfig {
        private final ManagedRetryClientStub managedClient = new ManagedRetryClientStub();
        private final ManagedServiceImpl managedService = new ManagedServiceImpl();

        @Bean
        public ManagedRetryClient managedRetryClient() {
            return managedClient;
        }

        @Bean
        public ManagedService managedService() {
            return managedService;
        }
    }

    @Configuration
    @EnableRetry
    public static class ManagedCustomRecoveryConfig {
        @Bean
        public ManagedRetryClient managedRetryClient() {
            return new ManagedRetryClientStub();
        }

        @Bean
        public ManagedCustomRecoveryService managedCustomRecoveryService() {
            return new ManagedCustomRecoveryServiceImpl();
        }
    }

    @Configuration
    @EnableRetry
    public static class ManagedAsyncConfig {
        private final ManagedRetryClientStub managedClient = new ManagedRetryClientStub();

        @Bean
        public ManagedRetryClient managedRetryClient() {
            return managedClient;
        }

        @Bean
        public ManagedAsyncService managedAsyncService() {
            return new ManagedAsyncServiceImpl();
        }
    }

    @Configuration
    @EnableRetry
    public static class ManagedWithoutClientConfig {
        private final ManagedVoidOnlyServiceImpl managedService = new ManagedVoidOnlyServiceImpl();

        @Bean
        public ManagedVoidOnlyService managedVoidOnlyService() {
            return managedService;
        }
    }

    @Configuration
    @EnableRetry
    public static class ReplayConfig {
        @Bean
        public ReplayHandler primaryReplayBean() {
            return new NamedReplayBean();
        }

        @Bean
        public ReplayHandler secondaryReplayBean() {
            return new NamedReplayBean();
        }
    }

    public static class CustomRecoveryHandler implements RecoveryHandler<String> {
        @Override
        public String taskName() {
            return "custom";
        }

        @Override
        public void recover(String payload, RecoveryContext context) {
        }
    }

    private static class ManagedRetryClientStub implements ManagedRetryClient {
        private final AtomicInteger submitCount = new AtomicInteger();

        @Override
        public <T> ManagedSubmitResult<T> submit(RetryTaskSpec<T> spec) {
            submitCount.incrementAndGet();
            return new ManagedSubmitResult.Existing<T>("task-1", RetryStatus.WAITING_RETRY, null);
        }
    }
}
