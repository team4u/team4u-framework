package com.team4u.framework.flow.bean;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.bean.exception.NoSuchBeanDefinitionException;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BeanOperationResolverTest {
    private static final String OPERATION_CLASS =
            "flowBeanResolverTest.operation.class.2b0cfb55";
    private static final String OPERATION_QUALIFIED =
            "flowBeanResolverTest.operation.qualified.2b0cfb55";
    private static final String POLICY_CLASS =
            "flowBeanResolverTest.policy.class.2b0cfb55";
    private static final String POLICY_QUALIFIED =
            "flowBeanResolverTest.policy.qualified.2b0cfb55";
    private static final String PERSISTENT_CLASS =
            "flowBeanResolverTest.persistent.class.2b0cfb55";
    private static final String PERSISTENT_QUALIFIED =
            "flowBeanResolverTest.persistent.qualified.2b0cfb55";
    private static final String BUILDER_PERSISTENT_CLASS =
            "flowBeanResolverTest.builder.persistent.class.2b0cfb55";
    private static final String PROXY_QUALIFIED =
            "flowBeanResolverTest.proxy.qualified.2b0cfb55";
    private static final String WRONG_TYPE =
            "flowBeanResolverTest.wrongType.2b0cfb55";
    private static final String MISSING_NAME =
            "flowBeanResolverTest.missing.2b0cfb55";

    @Test
    public void localCompileResolvesOperationClassAndQualifierBindingsViaSpi() {
        ClassOperation classOperation = new ClassOperation();
        QualifiedOperation qualifiedOperation = new QualifiedOperationImpl();
        register(OPERATION_CLASS, classOperation);
        register(OPERATION_QUALIFIED, qualifiedOperation);

        Flow<String, String> flow = Flow.<String, String>step(ClassOperation.class)
                .then(QualifiedOperation.class, OPERATION_QUALIFIED);

        assertEquals("input:class:qualified",
                Local.compile(flow).run("input").requireAccepted());
        assertEquals(1, classOperation.calls.get());
        assertEquals(1, ((QualifiedOperationImpl) qualifiedOperation).calls.get());
    }

    @Test
    public void resolvesPolicyClassAndQualifierBindings() {
        ClassPolicy classPolicy = new ClassPolicy();
        QualifiedPolicyImpl qualifiedPolicy = new QualifiedPolicyImpl();
        register(POLICY_CLASS, classPolicy);
        register(POLICY_QUALIFIED, qualifiedPolicy);

        Flow<String, String> flow = Flow.<String>identity()
                .policy(ClassPolicy.class, value -> value)
                .policy(QualifiedPolicy.class, POLICY_QUALIFIED, value -> value);

        assertEquals("input",
                Local.compile(flow, BeanOperationResolver.global()).run("input").requireAccepted());
        assertEquals(1, classPolicy.beforeCalls.get());
        assertEquals(1, classPolicy.afterCalls.get());
        assertEquals(1, qualifiedPolicy.beforeCalls.get());
        assertEquals(1, qualifiedPolicy.afterCalls.get());
    }

    @Test
    public void localCompileWithExplicitManagerResolvesPersistentPolicyBindings() {
        ClassPersistentPolicy classPolicy = new ClassPersistentPolicy();
        QualifiedPersistentPolicyImpl qualifiedPolicy = new QualifiedPersistentPolicyImpl();
        register(PERSISTENT_CLASS, classPolicy);
        register(PERSISTENT_QUALIFIED, qualifiedPolicy);

        Flow<String, String> flow = Flow.<String>identity()
                .persistentPolicy(ClassPersistentPolicy.class, value -> value)
                .persistentPolicy(QualifiedPersistentPolicy.class, PERSISTENT_QUALIFIED,
                        value -> value);

        assertEquals("input", Local.from(flow)
                .resolver(new BeanOperationResolver(BeanManager.getInstance()))
                .compile()
                .run("input").requireAccepted());
        assertPersistentCalls(classPolicy);
        assertPersistentCalls(qualifiedPolicy);
    }

    @Test
    public void localFromBuilderConfiguresCustomOptionsWithBeanResolver() {
        BuilderOperation op = new BuilderOperation();
        register(BUILDER_PERSISTENT_CLASS, op);

        Flow<String, String> flow = Flow.step(BuilderOperation.class, BUILDER_PERSISTENT_CLASS);

        LocalExecutable<String, String> exec = Local.from(flow)
                .flowId("bean-flow")
                .flowVersion(3)
                .compile();

        assertEquals("input:builder", exec.run("input").requireAccepted());
        assertEquals(1, op.calls.get());
    }

    @Test
    public void reportsStableMissingAndWrongTypeErrors() {
        BeanOperationResolver resolver = new BeanOperationResolver();

        assertResolutionFailure(NoSuchBeanDefinitionException.class,
                "No qualifying bean of type " + MissingOperation.class.getName(),
                () -> resolver.resolve(MissingOperation.class, null));
        assertResolutionFailure(NoSuchBeanDefinitionException.class,
                "No bean named '" + MISSING_NAME + "' for contract "
                        + MissingOperation.class.getName(),
                () -> resolver.resolve(MissingOperation.class, MISSING_NAME));

        register(WRONG_TYPE, "not-an-operation");
        assertResolutionFailure(IllegalStateException.class,
                "Bean named '" + WRONG_TYPE + "' has type java.lang.String but must implement "
                        + MissingOperation.class.getName(),
                () -> resolver.resolve(MissingOperation.class, WRONG_TYPE));
    }

    @Test
    public void preservesExactJdkProxyIdentityAndInvokesAdvice() {
        final AtomicInteger adviceCalls = new AtomicInteger();
        final ProxyOperation target = new ProxyOperationImpl();
        ProxyOperation proxy = (ProxyOperation) Proxy.newProxyInstance(
                ProxyOperation.class.getClassLoader(),
                new Class<?>[]{ProxyOperation.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object ignored, Method method, Object[] arguments)
                            throws Throwable {
                        if (method.getDeclaringClass() != Object.class) {
                            adviceCalls.incrementAndGet();
                        }
                        return method.invoke(target, arguments);
                    }
                });
        register(PROXY_QUALIFIED, proxy);

        BeanOperationResolver resolver = new BeanOperationResolver();
        assertSame(proxy, resolver.resolve(ProxyOperation.class, PROXY_QUALIFIED));
        assertEquals(ProxyOperation.class, resolver.implementationClass(proxy));
        assertEquals("input:proxy", Local.compile(
                Flow.step(ProxyOperation.class, PROXY_QUALIFIED), resolver)
                .run("input").requireAccepted());
        assertEquals(1, adviceCalls.get());
    }

    private static void register(String name, Object bean) {
        BeanManager manager = BeanManager.getInstance();
        manager.registerBean(name, bean);
        assertSame(bean, manager.getBean(name));
    }

    private static void assertPersistentCalls(PersistentCalls policy) {
        assertEquals(1, policy.initialCalls().get());
        assertEquals(1, policy.beforeCalls().get());
        assertEquals(1, policy.afterCalls().get());
    }

    private static void assertResolutionFailure(
            Class<? extends RuntimeException> type, String message, ThrowingRunnable action) {
        try {
            action.run();
            fail("Expected " + type.getName());
        } catch (RuntimeException error) {
            assertTrue("Unexpected exception: " + error, type.isInstance(error));
            assertEquals(message, error.getMessage());
        }
    }

    private interface ThrowingRunnable {
        void run();
    }

    public static final class ClassOperation implements Operation<String, String> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            calls.incrementAndGet();
            return Outcome.accepted(input + ":class");
        }
    }

    public interface QualifiedOperation extends Operation<String, String> { }

    public static final class QualifiedOperationImpl implements QualifiedOperation {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            calls.incrementAndGet();
            return Outcome.accepted(input + ":qualified");
        }
    }

    public static final class ClassPolicy implements Policy<String> {
        private final AtomicInteger beforeCalls = new AtomicInteger();
        private final AtomicInteger afterCalls = new AtomicInteger();

        @Override
        public Gate before(PolicyContext context, String key) {
            beforeCalls.incrementAndGet();
            return Gate.proceed();
        }

        @Override
        public void after(PolicyContext context, String key, Completion completion) {
            afterCalls.incrementAndGet();
        }
    }

    public interface QualifiedPolicy extends Policy<String> { }

    public static final class QualifiedPolicyImpl implements QualifiedPolicy {
        private final AtomicInteger beforeCalls = new AtomicInteger();
        private final AtomicInteger afterCalls = new AtomicInteger();

        @Override
        public Gate before(PolicyContext context, String key) {
            beforeCalls.incrementAndGet();
            return Gate.proceed();
        }

        @Override
        public void after(PolicyContext context, String key, Completion completion) {
            afterCalls.incrementAndGet();
        }
    }

    private interface PersistentCalls {
        AtomicInteger initialCalls();
        AtomicInteger beforeCalls();
        AtomicInteger afterCalls();
    }

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    public static final class ClassPersistentPolicy
            implements PersistentPolicy<String, Integer>, PersistentCalls {
        private final AtomicInteger initialCalls = new AtomicInteger();
        private final AtomicInteger beforeCalls = new AtomicInteger();
        private final AtomicInteger afterCalls = new AtomicInteger();

        @Override
        public Integer initialState(String key) {
            initialCalls.incrementAndGet();
            return 0;
        }

        @Override
        public Before<Integer> before(PolicyContext context, String key, Integer state) {
            beforeCalls.incrementAndGet();
            return PersistentPolicy.proceed(state + 1);
        }

        @Override
        public After<Integer> after(
                PolicyContext context, String key, Integer state, Completion completion) {
            afterCalls.incrementAndGet();
            return PersistentPolicy.returning(state);
        }
    }

    public interface QualifiedPersistentPolicy extends PersistentPolicy<String, Integer> { }

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    public static final class QualifiedPersistentPolicyImpl
            implements QualifiedPersistentPolicy, PersistentCalls {
        private final AtomicInteger initialCalls = new AtomicInteger();
        private final AtomicInteger beforeCalls = new AtomicInteger();
        private final AtomicInteger afterCalls = new AtomicInteger();

        @Override
        public Integer initialState(String key) {
            initialCalls.incrementAndGet();
            return 0;
        }

        @Override
        public Before<Integer> before(PolicyContext context, String key, Integer state) {
            beforeCalls.incrementAndGet();
            return PersistentPolicy.proceed(state + 1);
        }

        @Override
        public After<Integer> after(
                PolicyContext context, String key, Integer state, Completion completion) {
            afterCalls.incrementAndGet();
            return PersistentPolicy.returning(state);
        }
    }

    public interface MissingOperation extends Operation<String, String> { }

    public interface ProxyOperation extends Operation<String, String> { }

    public static final class ProxyOperationImpl implements ProxyOperation {
        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted(input + ":proxy");
        }
    }

    public static final class BuilderOperation implements Operation<String, String> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Outcome<String> execute(OperationContext context, String input) {
            calls.incrementAndGet();
            return Outcome.accepted(input + ":builder");
        }
    }
}
