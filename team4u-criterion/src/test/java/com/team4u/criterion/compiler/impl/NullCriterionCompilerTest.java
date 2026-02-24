package com.team4u.criterion.compiler.impl;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.MatchContext;
import com.team4u.criterion.model.NullCriterion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class NullCriterionCompilerTest {

    private final NullCriterionCompiler compiler = new NullCriterionCompiler();

    // ==================== Type.NULL 测试 ====================

    @Test
    public void nullType_actualIsNull_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NULL);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(null)));
    }

    @Test
    public void nullType_actualIsEmptyString_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NULL);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("")));
    }

    @Test
    public void nullType_actualIsNonEmptyString_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NULL);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("hello")));
    }

    @Test
    public void nullType_actualIsEmptyCollection_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NULL);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(new ArrayList<>())));
    }

    @Test
    public void nullType_actualIsEmptyMap_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NULL);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(new HashMap<>())));
    }

    // ==================== Type.EMPTY 测试 ====================

    @Test
    public void emptyType_actualIsNull_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.EMPTY);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(null)));
    }

    @Test
    public void emptyType_actualIsEmptyString_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.EMPTY);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("")));
    }

    @Test
    public void emptyType_actualIsEmptyCollection_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.EMPTY);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(new ArrayList<>())));
    }

    @Test
    public void emptyType_actualIsEmptyMap_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.EMPTY);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(new HashMap<>())));
    }

    @Test
    public void emptyType_actualIsNonEmptyString_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.EMPTY);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("hello")));
    }

    @Test
    public void emptyType_actualIsNonEmptyCollection_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.EMPTY);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(Arrays.asList("a", "b"))));
    }

    @Test
    public void emptyType_actualIsNonEmptyMap_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.EMPTY);
        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(map)));
    }

    // ==================== Type.NOT_EMPTY 测试 ====================

    @Test
    public void notEmptyType_actualIsNull_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NOT_EMPTY);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(null)));
    }

    @Test
    public void notEmptyType_actualIsEmptyString_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NOT_EMPTY);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of("")));
    }

    @Test
    public void notEmptyType_actualIsEmptyCollection_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NOT_EMPTY);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(new ArrayList<>())));
    }

    @Test
    public void notEmptyType_actualIsEmptyMap_returnsFalse() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NOT_EMPTY);
        Assert.assertFalse(compiler.compile(criterion, null).test(MatchContext.of(new HashMap<>())));
    }

    @Test
    public void notEmptyType_actualIsNonEmptyString_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NOT_EMPTY);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of("hello")));
    }

    @Test
    public void notEmptyType_actualIsNonEmptyCollection_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NOT_EMPTY);
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(Arrays.asList("a", "b"))));
    }

    @Test
    public void notEmptyType_actualIsNonEmptyMap_returnsTrue() {
        NullCriterion criterion = new NullCriterion(NullCriterion.Type.NOT_EMPTY);
        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        Assert.assertTrue(compiler.compile(criterion, null).test(MatchContext.of(map)));
    }
}
