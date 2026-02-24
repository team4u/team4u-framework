package com.team4u.criterion.compiler.impl;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.MatchContext;
import com.team4u.criterion.model.InCriterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class InCriterionCompilerTest {

    private final InCriterionCompiler criterionCompiler = new InCriterionCompiler();

    @Test
    public void compileStatic() {
        InCriterion criterion = new InCriterion(
                values("a", "b"),
                false);

        // 验证结果
        Assert.assertTrue(criterionCompiler.compile(criterion, null).test(new MatchContext("a")));
        Assert.assertTrue(criterionCompiler.compile(criterion, null).test(new MatchContext("b")));
        Assert.assertFalse(criterionCompiler.compile(criterion, null).test(new MatchContext("c")));
    }

    @Test
    public void compileStaticWithNumber() {
        InCriterion criterion = new InCriterion(
                values(1, 2.0, "3.00"),
                false);

        // 验证结果 (数值兼容性与标准化)
        Assert.assertTrue("Integer matches", criterionCompiler.compile(criterion, null).test(new MatchContext(1)));
        Assert.assertTrue("BigDecimal scale matches", criterionCompiler.compile(criterion, null).test(new MatchContext(new BigDecimal("1.000"))));
        Assert.assertTrue("Double matches", criterionCompiler.compile(criterion, null).test(new MatchContext(2)));
        Assert.assertTrue("String number matches", criterionCompiler.compile(criterion, null).test(new MatchContext(3.0)));
        Assert.assertTrue("String matches", criterionCompiler.compile(criterion, null).test(new MatchContext("3")));
        Assert.assertFalse("Not in list", criterionCompiler.compile(criterion, null).test(new MatchContext(4)));
    }

    @Test
    public void compileStaticWithMixedTypes() {
        // 包含数值、字符串和空值
        InCriterion criterion = new InCriterion(
                values(1, "abc", null),
                false);

        Assert.assertTrue("Exact number match", criterionCompiler.compile(criterion, null).test(new MatchContext(1)));
        Assert.assertTrue("BigDecimal match with integer", criterionCompiler.compile(criterion, null).test(new MatchContext(new BigDecimal("1.0"))));
        Assert.assertTrue("String match", criterionCompiler.compile(criterion, null).test(new MatchContext("abc")));
        Assert.assertFalse("Non-matching string", criterionCompiler.compile(criterion, null).test(new MatchContext("def")));
    }

    @Test
    public void compileDynamicWithNumber() {
        // 动态场景下的数值兼容性
        List<Value<Object>> values = new ArrayList<>();
        values.add(context -> context.getAttribute("var"));

        InCriterion criterion = new InCriterion(values, false);

        MatchContext context = new MatchContext(1.0);
        context.setAttribute("var", 1);

        Assert.assertTrue("Dynamic number compatibility", criterionCompiler.compile(criterion, null).test(context));
    }

    @Test
    public void compileNotWithNumber() {
        InCriterion criterion = new InCriterion(
                values(1, 2.0),
                true);

        Assert.assertFalse("1 is in list, so not should be false", criterionCompiler.compile(criterion, null).test(new MatchContext(1.0)));
        Assert.assertTrue("3 is not in list, so not should be true", criterionCompiler.compile(criterion, null).test(new MatchContext(3)));
    }

    @Test
    public void compileDynamic() {
        // 混合：上下文变量 + 静态值
        List<Value<Object>> values = new ArrayList<>();
        values.add(new FixedValue<>("static"));
        values.add(context -> context.getAttribute("var")); // 动态值

        InCriterion criterion = new InCriterion(values, false);

        MatchContext context = new MatchContext("dynamic");
        context.setAttribute("var", "dynamic");

        // 验证
        Assert.assertTrue(criterionCompiler.compile(criterion, null).test(context));

        MatchContext context2 = new MatchContext("static");
        Assert.assertTrue(criterionCompiler.compile(criterion, null).test(context2));

        MatchContext context3 = new MatchContext("other");
        Assert.assertFalse(criterionCompiler.compile(criterion, null).test(context3));
    }

    @Test
    public void compileNot() {
        InCriterion criterion = new InCriterion(
                values("a", "b"),
                true);

        Assert.assertFalse(criterionCompiler.compile(criterion, null).test(new MatchContext("a")));
        Assert.assertTrue(criterionCompiler.compile(criterion, null).test(new MatchContext("c")));
    }

    private List<Value<Object>> values(Object... objs) {
        List<Value<Object>> list = new ArrayList<>();
        for (Object obj : objs) {
            list.add(new FixedValue<>(obj));
        }
        return list;
    }
}
