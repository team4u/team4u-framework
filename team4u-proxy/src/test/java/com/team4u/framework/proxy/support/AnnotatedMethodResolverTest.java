package com.team4u.framework.proxy.support;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import org.junit.Assert;
import org.junit.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AnnotatedMethodResolver} 注解解析覆盖性测试：
 * 普通方法、桥接方法、注解只在接口、注解只在实现类、父类/父接口链、类级注解、
 * JDK 代理场景经 targetClass 命中（singleflight 漏判 bug 的修复语义）
 *
 * @author jay.wu
 */
public class AnnotatedMethodResolverTest {

    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @Documented
    public @interface Marker {

        String value();
    }

    private final AnnotatedMethodResolver<Marker> resolver = AnnotatedMethodResolver.of(Marker.class);

    /**
     * 场景 1：注解直接标注在实现类的普通方法上
     */
    @Test
    public void plainImplementationMethod() throws Exception {
        Method method = StringRepo.class.getMethod("find", String.class);
        Marker marker = resolver.resolve(method, StringRepo.class);

        Assert.assertNotNull(marker);
        Assert.assertEquals("impl", marker.value());
        Assert.assertTrue(resolver.isAnnotated(method, StringRepo.class));
    }

    /**
     * 场景 2：桥接方法——泛型实现类中编译器生成的桥接方法应还原到真实业务方法并命中其注解
     */
    @Test
    public void bridgeMethodResolvesToRealMethod() throws Exception {
        Method bridge = null;
        for (Method method : StringRepo.class.getDeclaredMethods()) {
            if ("find".equals(method.getName()) && method.isBridge()) {
                bridge = method;
                break;
            }
        }
        Assert.assertNotNull("泛型实现类应生成桥接方法", bridge);

        // resolveBridgeMethod 应还原出非桥接的真实方法
        Method resolved = resolver.resolveBridgeMethod(bridge);
        Assert.assertFalse(resolved.isBridge());
        Assert.assertEquals(String.class, resolved.getReturnType());

        // 经解析器应命中真实方法上的注解
        Marker marker = resolver.resolve(bridge, StringRepo.class);
        Assert.assertNotNull("桥接方法应还原到真实方法并命中注解", marker);
        Assert.assertEquals("impl", marker.value());
    }

    /**
     * 场景 3：注解只标注在接口方法上，实现类未标注
     */
    @Test
    public void annotationOnlyOnInterfaceMethod() throws Exception {
        Method method = AnnotatedInterface.class.getMethod("run", String.class);

        Assert.assertEquals("iface", resolver.resolve(method, PlainImpl.class).value());
    }

    /**
     * 场景 4：注解只标注在实现类方法上，经接口方法 + targetClass 命中
     * （JDK 代理下拦截到的是接口方法——singleflight 漏判 bug 的修复语义）
     */
    @Test
    public void annotationOnlyOnImplementationViaTargetClass() throws Exception {
        Method interfaceMethod = BareInterface.class.getMethod("run", String.class);
        Assert.assertNull("接口方法自身无注解", interfaceMethod.getAnnotation(Marker.class));

        Marker marker = resolver.resolve(interfaceMethod, AnnotatedImpl.class);

        Assert.assertNotNull("经 targetClass 应命中实现方法上的注解", marker);
        Assert.assertEquals("impl", marker.value());
    }

    /**
     * 场景 5：泛型接口的 JDK 代理调用链路上，拦截器内经 targetClass 解析命中实现方法注解
     */
    @Test
    public void jdkProxyInvocationResolvesViaTargetClass() throws Exception {
        final AtomicReference<Marker> resolved = new AtomicReference<>();
        MethodInterceptor interceptor = new MethodInterceptor() {
            private final AnnotatedMethodResolver<Marker> inner = AnnotatedMethodResolver.of(Marker.class);

            @Override
            public Object invoke(MethodInvocation invocation) throws Throwable {
                Class<?> targetClass = invocation.getTarget() == null
                        ? invocation.getMethod().getDeclaringClass()
                        : invocation.getTarget().getClass();
                resolved.set(inner.resolve(invocation.getMethod(), targetClass));
                return invocation.proceed();
            }
        };

        @SuppressWarnings("unchecked")
        Repo<String> proxy = (Repo<String>) ProxyBuilder.forClass((Class<Repo<String>>) (Class<?>) Repo.class)
                .delegate(new StringRepo())
                .intercept(interceptor)
                .build();

        Assert.assertEquals("id-1", proxy.find("id-1"));
        Assert.assertNotNull("JDK 代理拦截到的接口（擦除）方法应经 targetClass 命中实现注解",
                resolved.get());
        Assert.assertEquals("impl", resolved.get().value());
    }

    /**
     * 场景 6：接口与实现类同时标注时，实现类（更具体）优先
     */
    @Test
    public void implementationAnnotationWinsOverInterface() throws Exception {
        Method method = AnnotatedInterface.class.getMethod("run", String.class);

        Assert.assertEquals("impl", resolver.resolve(method, OverrideImpl.class).value());
    }

    /**
     * 场景 7：注解标注在父接口方法上，经多层接口与实现链路命中
     */
    @Test
    public void annotationOnParentInterfaceMethod() throws Exception {
        Method method = ChildInterface.class.getMethod("run", String.class);

        Assert.assertEquals("parent", resolver.resolve(method, ChildImpl.class).value());
    }

    /**
     * 场景 8：注解标注在父类方法上，子类覆盖未标注
     */
    @Test
    public void annotationOnSuperclassMethod() throws Exception {
        Method overridden = SubOverride.class.getMethod("run", String.class);

        Marker marker = resolver.resolve(overridden, SubOverride.class);

        Assert.assertNotNull("父类方法注解应经继承链命中", marker);
        Assert.assertEquals("base", marker.value());
    }

    /**
     * 场景 9：类级注解兜底（方法级均未标注时），@Inherited 支持父类类级注解传递
     */
    @Test
    public void classLevelAnnotationFallback() throws Exception {
        Method method = TypeLevelService.class.getMethod("run", String.class);
        Assert.assertEquals("type", resolver.resolve(method, TypeLevelService.class).value());

        Method inheritedMethod = SubTypeService.class.getMethod("run", String.class);
        Assert.assertEquals("superType", resolver.resolve(inheritedMethod, SubTypeService.class).value());
    }

    /**
     * 场景 10：完全无注解的方法返回 null；method 为 null 安全返回 null
     * <p>
     * 注：PlainImpl 虽自身未标注，但其实现的 AnnotatedInterface.run 带注解，
     * 接口声明注解对实现生效是预期语义，故不可作为“无注解”用例。
     */
    @Test
    public void notAnnotatedReturnsNull() throws Exception {
        Method method = CleanService.class.getMethod("run", String.class);

        Assert.assertNull(resolver.resolve(method, CleanService.class));
        Assert.assertFalse(resolver.isAnnotated(method, CleanService.class));
        Assert.assertNull(resolver.resolve(null, CleanService.class));
    }

    /**
     * 场景 11：缓存以 (method, targetClass) 为键——不同 targetClass 的解析结果互不串扰
     */
    @Test
    public void cacheKeyIncludesTargetClass() throws Exception {
        Method method = BareInterface.class.getMethod("run", String.class);

        Assert.assertEquals("implA", resolver.resolve(method, AnnotatedImplA.class).value());
        Assert.assertEquals("implB", resolver.resolve(method, AnnotatedImplB.class).value());
        // 重复解析命中缓存，结果稳定
        Assert.assertSame(resolver.resolve(method, AnnotatedImplA.class),
                resolver.resolve(method, AnnotatedImplA.class));
    }

    // ------------------------------------------------- 测试类型定义

    /**
     * 泛型接口（public：与生产场景一致，避免包私有接口反射调用限制）
     */
    public interface Repo<T> {

        T find(String id);
    }

    public static class StringRepo implements Repo<String> {

        @Marker("impl")
        public String find(String id) {
            return id;
        }
    }

    interface AnnotatedInterface {

        @Marker("iface")
        String run(String value);
    }

    interface BareInterface {

        String run(String value);
    }

    static class PlainImpl implements AnnotatedInterface {

        @Override
        public String run(String value) {
            return value;
        }
    }

    static class AnnotatedImpl implements BareInterface {

        @Marker("impl")
        @Override
        public String run(String value) {
            return value;
        }
    }

    static class OverrideImpl implements AnnotatedInterface {

        @Marker("impl")
        @Override
        public String run(String value) {
            return value;
        }
    }

    static class AnnotatedImplA implements BareInterface {

        @Marker("implA")
        @Override
        public String run(String value) {
            return "A";
        }
    }

    static class AnnotatedImplB implements BareInterface {

        @Marker("implB")
        @Override
        public String run(String value) {
            return "B";
        }
    }

    interface ParentInterface {

        @Marker("parent")
        String run(String value);
    }

    interface ChildInterface extends ParentInterface {
    }

    static class ChildImpl implements ChildInterface {

        @Override
        public String run(String value) {
            return value;
        }
    }

    static class BaseClass {

        @Marker("base")
        public String run(String value) {
            return value;
        }
    }

    static class SubOverride extends BaseClass {

        @Override
        public String run(String value) {
            return value;
        }
    }

    @Marker("type")
    static class TypeLevelService {

        public String run(String value) {
            return value;
        }
    }

    @Marker("superType")
    static class SuperTypeService {

        public String run(String value) {
            return value;
        }
    }

    static class SubTypeService extends SuperTypeService {
    }

    /**
     * 完全未标注注解的类型
     */
    static class CleanService {

        public String run(String value) {
            return value;
        }
    }
}
