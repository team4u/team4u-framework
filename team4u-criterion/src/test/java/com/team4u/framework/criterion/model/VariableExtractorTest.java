package com.team4u.framework.criterion.model;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.model.value.FixedValue;
import com.team4u.framework.criterion.model.value.VariableValue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

public class VariableExtractorTest {

    @Test
    public void testComplexScenarios() {
        // (age > $minAge && role == 'admin') || status in $activeStatuses
        Criterion ageGreaterThanMin = new PropertyCriterion("age",
                new SmartCompareCriterion(">", new VariableValue<>("minAge", BigDecimal.class)));

        Criterion roleIsAdmin = new PropertyCriterion("role",
                new SmartCompareCriterion("==", new FixedValue<>("admin")));

        Criterion and = new LogicCriterion(LogicCriterion.Operator.AND,
                Arrays.asList(ageGreaterThanMin, roleIsAdmin));

        Criterion statusInLevels = new PropertyCriterion("status",
                new InCriterion(Collections
                        .singletonList(new VariableValue<>("activeStatuses", Object.class)),
                        false));

        Criterion or = new LogicCriterion(LogicCriterion.Operator.OR, Arrays.asList(and, statusInLevels));

        Set<String> variables = VariableExtractor.extract(or);

        Assert.assertTrue(variables.contains("age"));
        Assert.assertTrue(variables.contains("minAge"));
        Assert.assertTrue(variables.contains("role"));
        Assert.assertTrue(variables.contains("status"));
        Assert.assertTrue(variables.contains("activeStatuses"));
        Assert.assertEquals(5, variables.size());
    }

    @Test
    public void testBetweenCriterion() {
        // score between [$minScore, $maxScore]
        Criterion between = new PropertyCriterion("score",
                new BetweenCriterion(
                        new VariableValue<>("minScore", Integer.class),
                        new VariableValue<>("maxScore", Integer.class),
                        true, true, null));

        Set<String> variables = VariableExtractor.extract(between);
        Assert.assertTrue(variables.contains("score"));
        Assert.assertTrue(variables.contains("minScore"));
        Assert.assertTrue(variables.contains("maxScore"));
        Assert.assertEquals(3, variables.size());
    }

    @Test
    public void testOtherCriteria() {
        // 测试 ContainsCriterion
        Criterion contains = new PropertyCriterion("tags",
                new ContainsCriterion(new VariableValue<>("tag", String.class)));
        Assert.assertTrue(VariableExtractor.extract(contains).contains("tags"));
        Assert.assertTrue(VariableExtractor.extract(contains).contains("tag"));

        // 测试 ProbabilityCriterion
        Criterion prob = new ProbabilityCriterion(new VariableValue<>("rate", Number.class));
        Assert.assertTrue(VariableExtractor.extract(prob).contains("rate"));

        // 测试 HashProbabilityCriterion
        Criterion hashProb = new PropertyCriterion("uid",
                new HashProbabilityCriterion(new VariableValue<>("hashRate", Number.class)));
        Assert.assertTrue(VariableExtractor.extract(hashProb).contains("uid"));
        Assert.assertTrue(VariableExtractor.extract(hashProb).contains("hashRate"));

        // 测试 DynamicCriterion
        Criterion dynamic = new DynamicCriterion("custom",
                new VariableValue<>("dynamicVar", Object.class),
                (a, b) -> true);
        Assert.assertTrue(VariableExtractor.extract(dynamic).contains("dynamicVar"));

        // 测试 ComparableCriterion
        Criterion comparable = new ComparableCriterion(">",
                new VariableValue<>("compVar", String.class),
                null);
        Assert.assertTrue(VariableExtractor.extract(comparable).contains("compVar"));
    }

    @Test
    public void testNull() {
        Assert.assertTrue(VariableExtractor.extract(null).isEmpty());
    }
}
