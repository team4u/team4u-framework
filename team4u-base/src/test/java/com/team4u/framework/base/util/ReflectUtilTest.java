package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;

/**
 * ReflectUtil 单元测试
 *
 * @author AI
 */
public class ReflectUtilTest {

    @Test
    public void testGetField() {
        // 1. 获取当前类的字段
        Field field = ReflectUtil.getField(TestChildClass.class, "childField");
        Assert.assertNotNull("应该能获取到当前类的字段", field);
        Assert.assertEquals("childField", field.getName());

        // 2. 获取父类的字段
        Field parentField = ReflectUtil.getField(TestChildClass.class, "parentField");
        Assert.assertNotNull("应该能获取到父类的字段", parentField);
        Assert.assertEquals("parentField", parentField.getName());

        // 3. 多次获取，验证缓存机制（功能上表现为返回相同的Field对象或能正常返回）
        Field field2 = ReflectUtil.getField(TestChildClass.class, "childField");
        Assert.assertEquals("多次获取应该返回相同的Field对象", field, field2);

        // 4. 获取不存在的字段
        Field notExistField = ReflectUtil.getField(TestChildClass.class, "notExistField");
        Assert.assertNull("获取不存在的字段应该返回 null", notExistField);
    }

    @Test
    public void testSetFieldValue() {
        TestChildClass obj = new TestChildClass();

        // 1. 设置当前类的私有字段
        ReflectUtil.setFieldValue(obj, "childField", "newChildValue");
        Assert.assertEquals("newChildValue", obj.getChildField());

        // 2. 设置父类的私有字段
        ReflectUtil.setFieldValue(obj, "parentField", "newParentValue");
        Assert.assertEquals("newParentValue", obj.getParentField());
    }

    // 测试用的辅助类
    static class TestParentClass {
        private final String parentField = "parent";

        public String getParentField() {
            return parentField;
        }
    }

    static class TestChildClass extends TestParentClass {
        private final String childField = "child";

        public String getChildField() {
            return childField;
        }
    }
}
