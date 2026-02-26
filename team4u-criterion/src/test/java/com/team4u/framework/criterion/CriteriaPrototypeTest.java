package com.team4u.framework.criterion;

import com.team4u.framework.criterion.compiler.CompilerRegistry;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import org.junit.Assert;
import org.junit.Test;

/**
 * 规则引擎原型及隔离性测试
 */
public class CriteriaPrototypeTest {

    @Test
    public void testStandardDefaultsLoading() {
        // 验证全局原型是否正确加载了内置项
        Assert.assertFalse("全局原型编译器不应为空", CompilerRegistry.global().getPolicies().isEmpty());
        Assert.assertFalse("全局原型转换器不应为空", ValueConverterRegistry.global().getPolicies().isEmpty());
    }

    @Test
    public void testInstanceIsolation() {
        // 1. 获取两个独立的 Builder
        Criteria.Builder builderA = Criteria.builder();
        Criteria.Builder builderB = Criteria.builder();

        // 2. 验证初始状态一致
        Assert.assertEquals(builderA.getCompilerRegistry().getPolicies().size(),
                builderB.getCompilerRegistry().getPolicies().size());

        // 3. 修改 A 的编译器，不影响 B
        builderA.clear();
        Assert.assertTrue("BuilderA 清理后应为空", builderA.getCompilerRegistry().getPolicies().isEmpty());
        Assert.assertFalse("BuilderB 不应受 BuilderA 影响", builderB.getCompilerRegistry().getPolicies().isEmpty());
    }

    @Test
    public void testClearMethod() {
        Criteria.Builder builder = Criteria.builder().clear();
        Assert.assertTrue("clear() 后编译器注册表应为空", builder.getCompilerRegistry().getPolicies().isEmpty());
        Assert.assertTrue("clear() 后转换器注册表应为空", builder.getConverterRegistry().getPolicies().isEmpty());
    }

    @Test
    public void testPrototypeCopyPerformanceSim() {
        // 模拟高频创建 Builder 的场景，确保没有显著延迟
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            Criteria.builder();
        }
        long end = System.currentTimeMillis();
        System.out.println("1000次 Builder 创建耗时: " + (end - start) + "ms");
    }
}
