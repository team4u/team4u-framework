package com.team4u.framework.criterion.compiler.impl;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.model.ContainsCriterion;
import com.team4u.framework.criterion.model.value.FixedValue;

import java.util.*;

public class ContainsCriterionCompilerTest {

    private final ContainsCriterionCompiler compiler = new ContainsCriterionCompiler();

    // ==================== null 值处理测试 ====================

    @Test
    public void actualIsNull_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("test");
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(null)));
    }

    // ==================== List 集合测试 ====================

    @Test
    public void list_containsExactElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        List<String> list = Arrays.asList("apple", "banana", "cherry");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_notContainsElement_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("grape");
        List<String> list = Arrays.asList("apple", "banana", "cherry");
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_containsIntegerElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion(2);
        List<Integer> list = Arrays.asList(1, 2, 3);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_emptyList_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("test");
        List<String> list = Collections.emptyList();
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_containsNullElement_searchForNull() {
        // 使用 FixedValue 包装 null，避免构造方法歧义
        ContainsCriterion criterion = new ContainsCriterion(new FixedValue<>(null));
        List<String> list = Arrays.asList("apple", null, "cherry");
        // expected 是 null，通过 Convert.toStr 转换后比较
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    // ==================== Set 集合测试 ====================

    @Test
    public void set_containsExactElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        Set<String> set = new HashSet<>(Arrays.asList("apple", "banana", "cherry"));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(set)));
    }

    @Test
    public void set_notContainsElement_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("grape");
        Set<String> set = new HashSet<>(Arrays.asList("apple", "banana", "cherry"));
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(set)));
    }

    @Test
    public void set_emptySet_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("test");
        Set<String> set = Collections.emptySet();
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(set)));
    }

    // ==================== Array 数组测试 ====================

    @Test
    public void array_containsExactElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        String[] array = {"apple", "banana", "cherry"};
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(array)));
    }

    @Test
    public void array_notContainsElement_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("grape");
        String[] array = {"apple", "banana", "cherry"};
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(array)));
    }

    @Test
    public void array_intArray_containsElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion(2);
        Integer[] array = {1, 2, 3};
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(array)));
    }

    @Test
    public void array_primitiveIntArray_containsElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion(2);
        int[] array = {1, 2, 3};
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(array)));
    }

    @Test
    public void array_emptyArray_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("test");
        String[] array = {};
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(array)));
    }

    // ==================== Iterable 测试 ====================

    @Test
    public void iterable_containsElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        Iterable<String> iterable = () -> Arrays.asList("apple", "banana", "cherry").iterator();
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(iterable)));
    }

    @Test
    public void iterable_notContainsElement_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("grape");
        Iterable<String> iterable = () -> Arrays.asList("apple", "banana", "cherry").iterator();
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(iterable)));
    }

    // ==================== Iterator 测试 ====================

    @Test
    public void iterator_containsElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        Iterator<String> iterator = Arrays.asList("apple", "banana", "cherry").iterator();
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(iterator)));
    }

    @Test
    public void iterator_notContainsElement_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("grape");
        Iterator<String> iterator = Arrays.asList("apple", "banana", "cherry").iterator();
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(iterator)));
    }

    // ==================== String 子串匹配测试 ====================

    @Test
    public void string_containsSubstring_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("world");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("hello world")));
    }

    @Test
    public void string_containsSubstringAtBeginning_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("hello");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("hello world")));
    }

    @Test
    public void string_containsSubstringAtEnd_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("world");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("hello world")));
    }

    @Test
    public void string_containsExactString_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("hello world");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("hello world")));
    }

    @Test
    public void string_notContainsSubstring_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("foo");
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("hello world")));
    }

    @Test
    public void string_emptySubstring_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("hello world")));
    }

    @Test
    public void string_emptyActual_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("test");
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("")));
    }

    @Test
    public void string_caseSensitive_returnsFalse() {
        ContainsCriterion criterion = new ContainsCriterion("WORLD");
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("hello world")));
    }

    // ==================== 不同类型数字兼容性测试 (Convert.toStr 转换逻辑验证) ====================

    @Test
    public void list_integerVsDouble_sameValue_returnsTrue() {
        // 集合中包含 Integer 1，expected 是 Double 1.0
        // ObjectCompareUtil 会进行数值类型对其比较
        ContainsCriterion criterion = new ContainsCriterion(1.0);
        List<Integer> list = Arrays.asList(1, 2, 3);
        // 应该返回 true
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_doubleVsInteger_sameValue_returnsTrue() {
        // 集合中包含 Double 1.0，expected 是 Integer 1
        ContainsCriterion criterion = new ContainsCriterion(1);
        List<Double> list = Arrays.asList(1.0, 2.0, 3.0);
        // 应该返回 true
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_longVsInteger_sameValue_returnsTrue() {
        // 集合中包含 Long 1L，expected 是 Integer 1
        ContainsCriterion criterion = new ContainsCriterion(1);
        List<Long> list = Arrays.asList(1L, 2L, 3L);
        // Convert.toStr(1L) = "1", Convert.toStr(1) = "1"
        // 字符串相等，应该返回 true
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_integerVsLong_sameValue_returnsTrue() {
        // 集合中包含 Integer 1，expected 是 Long 1L
        ContainsCriterion criterion = new ContainsCriterion(1L);
        List<Integer> list = Arrays.asList(1, 2, 3);
        // Convert.toStr(1) = "1", Convert.toStr(1L) = "1"
        // 字符串相等，应该返回 true
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_stringVsInteger_sameValue_returnsTrue() {
        // 集合中包含 String "1"，expected 是 Integer 1
        ContainsCriterion criterion = new ContainsCriterion(1);
        List<String> list = Arrays.asList("1", "2", "3");
        // Convert.toStr("1") = "1", Convert.toStr(1) = "1"
        // 字符串相等，应该返回 true
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_integerVsString_sameValue_returnsTrue() {
        // 集合中包含 Integer 1，expected 是 String "1"
        ContainsCriterion criterion = new ContainsCriterion("1");
        List<Integer> list = Arrays.asList(1, 2, 3);
        // Convert.toStr(1) = "1", Convert.toStr("1") = "1"
        // 字符串相等，应该返回 true
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_shortVsInteger_sameValue_returnsTrue() {
        // 集合中包含 Short 1，expected 是 Integer 1
        ContainsCriterion criterion = new ContainsCriterion(1);
        List<Short> list = Arrays.asList((short) 1, (short) 2, (short) 3);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void list_byteVsInteger_sameValue_returnsTrue() {
        // 集合中包含 Byte 1，expected 是 Integer 1
        ContainsCriterion criterion = new ContainsCriterion(1);
        List<Byte> list = Arrays.asList((byte) 1, (byte) 2, (byte) 3);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    // ==================== 边界情况测试 ====================

    @Test
    public void unsupportedType_returnsFalse() {
        // actual 既不是集合也不是字符串
        ContainsCriterion criterion = new ContainsCriterion("test");
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(123)));
    }

    @Test
    public void stringActualWithNonStringExpected_returnsFalse() {
        // actual 是 String，但 expected 不是 String
        ContainsCriterion criterion = new ContainsCriterion(123);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("hello 123 world")));
    }

    @Test
    public void list_containsMultipleSameElements_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        List<String> list = Arrays.asList("apple", "apple", "apple");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void linkedList_containsElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        LinkedList<String> list = new LinkedList<>(Arrays.asList("apple", "banana", "cherry"));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(list)));
    }

    @Test
    public void treeSet_containsElement_returnsTrue() {
        ContainsCriterion criterion = new ContainsCriterion("apple");
        TreeSet<String> set = new TreeSet<>(Arrays.asList("apple", "banana", "cherry"));
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(set)));
    }
}
