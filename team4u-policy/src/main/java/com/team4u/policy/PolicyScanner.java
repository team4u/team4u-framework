package com.team4u.policy;

import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.log.Log;
import com.team4u.policy.util.ServiceLoaderUtil;

import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * 策略扫描器
 *
 * @author jay.wu
 */
public class PolicyScanner {

    private static final Log log = Log.get();

    /**
     * 通过 ServiceLoader 注册策略
     */
    public static <P> void registerFromServiceLoader(PolicyRegistry<P> registry) {
        ServiceLoaderUtil.loadAvailableList((Class<? extends P>) registry.getPolicyClass()).forEach(registry::register);
    }

    /**
     * 将包下扫描到的策略注入到注册表中
     * <p>
     * 自动通过 policyClass 所在的包路径进行扫描
     */
    public static <P> void scanAndRegister(PolicyRegistry<P> registry) {
        Class<? extends P> policyClass = registry.getPolicyClass();
        scanAndRegister(registry, policyClass.getPackage().getName(), policyClass);
    }

    /**
     * 将包下扫描到的策略注入到注册表中
     */
    @SuppressWarnings("unchecked")
    public static <P> void scanAndRegister(
            PolicyRegistry<P> registry,
            String packageName,
            Class<? extends P> policyClass) {

        ClassUtil.scanPackageBySuper(packageName, policyClass).stream()
                .filter(it -> {
                    // 必须是可以实例化的正常类，且不是匿名类、非静态内部类、本地类等，且不是合成类
                    return !it.isInterface()
                            && !Modifier.isAbstract(it.getModifiers())
                            && !it.isAnonymousClass()
                            && !it.isLocalClass()
                            && (!it.isMemberClass() || Modifier.isStatic(it.getModifiers()))
                            && !it.isSynthetic();
                })
                .map(clazz -> {
                    try {
                        return (P) ReflectUtil.newInstance(clazz);
                    } catch (Exception e) {
                        log.info("PolicyScanner|scanAndRegister|fail|class={}|msg={}", clazz.getName(), e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .forEach(registry::register);
    }
}
