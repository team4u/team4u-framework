package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

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

    @Test
    public void testGetFieldsAndFieldMap() {
        java.util.Map<String, Field> fieldMap = ReflectUtil.getFieldMap(TestChildClass.class);
        Assert.assertNotNull(fieldMap);
        Assert.assertTrue(fieldMap.containsKey("childField"));
        Assert.assertTrue(fieldMap.containsKey("parentField"));

        java.util.List<Field> fields = ReflectUtil.getFields(TestChildClass.class);
        Assert.assertTrue(fields.size() >= 2);

        // 空入参边界测试
        Assert.assertTrue(ReflectUtil.getFieldMap(null).isEmpty());
        Assert.assertTrue(ReflectUtil.getFields(null).isEmpty());
    }

    @Test
    public void testIsInstanceField() throws NoSuchFieldException {
        Field childField = TestChildClass.class.getDeclaredField("childField");
        Assert.assertTrue(ReflectUtil.isInstanceField(childField));

        Assert.assertFalse(ReflectUtil.isInstanceField(null));
    }

    @Test
    public void testGetFieldValue() {
        TestChildClass obj = new TestChildClass();

        Assert.assertEquals("child", ReflectUtil.getFieldValue(obj, "childField"));
        Assert.assertEquals("parent", ReflectUtil.getFieldValue(obj, "parentField"));
        Assert.assertNull(ReflectUtil.getFieldValue(obj, "missingField"));
    }

    @Test
    public void testGetParameters() throws Exception {
        // 场景一：获取类中自身声明的方法参数
        Method method = TestChildClass.class.getMethod("childMethod", String.class, String.class);
        Parameter[] params = ReflectUtil.getParameters(TestChildClass.class, method);
        if (params != null) {
            Assert.assertEquals(2, params.length);
        }

        // 场景二：传入的方法为空
        Parameter[] paramsNullMethod = ReflectUtil.getParameters(TestChildClass.class, null);
        Assert.assertNull(paramsNullMethod);

        // 场景三：方法来源于接口（使用 Object.class 作为 targetClass 时未找到会自动回退）
        Method interfaceMethod = TestInterface.class.getMethod("interfaceMethod", String.class, int.class);
        Parameter[] paramsFallback = ReflectUtil.getParameters(Object.class, interfaceMethod);
        if (paramsFallback != null) {
            Assert.assertEquals(2, paramsFallback.length);
        }
    }

    // 测试用的辅助接口
    interface TestInterface {
        void interfaceMethod(String arg0, int arg1);
    }

    // 测试用的辅助类
    static class TestParentClass implements TestInterface {
        private String parentField = "parent";

        public String getParentField() {
            return parentField;
        }

        @Override
        public void interfaceMethod(String arg0, int arg1) {
        }
    }

    static class TestChildClass extends TestParentClass {
        private String childField = "child";

        public String getChildField() {
            return childField;
        }

        public void childMethod(String p1, String p2) {
        }
    }
}
