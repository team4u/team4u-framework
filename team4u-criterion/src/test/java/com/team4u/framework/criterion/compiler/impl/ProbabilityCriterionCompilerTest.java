package com.team4u.framework.criterion.compiler.impl;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.model.ProbabilityCriterion;

import java.math.BigDecimal;

public class ProbabilityCriterionCompilerTest {

    private final ProbabilityCriterionCompiler compiler = new ProbabilityCriterionCompiler();

    /**
     * 统计在多次匹配中命中的次数
     */
    private int countHits(ProbabilityCriterion criterion, int totalTests) {
        int hitCount = 0;
        for (int i = 0; i < totalTests; i++) {
            if (compiler.compile(criterion, null).test(MatchContext.of(null))) {
                hitCount++;
            }
        }
        return hitCount;
    }

    /**
     * 统计匹配次数，支持传入不同的 actual 值
     */
    private int countHits(ProbabilityCriterion criterion, Object actual, int totalTests) {
        int hitCount = 0;
        for (int i = 0; i < totalTests; i++) {
            if (compiler.compile(criterion, null).test(MatchContext.of(actual))) {
                hitCount++;
            }
        }
        return hitCount;
    }

    // ==================== 基础测试 ====================

    @Test
    public void key_returnsProbabilityCriterion() {
        Assert.assertEquals(ProbabilityCriterion.class, compiler.key());
    }

    // ==================== 阈值边界测试 ====================

    @Test
    public void thresholdZero_neverMatches() {
        // 阈值 0 永远不会命中
        ProbabilityCriterion criterion = new ProbabilityCriterion(BigDecimal.ZERO);
        int hitCount = countHits(criterion, 1000);
        Assert.assertEquals(0, hitCount);
    }

    @Test
    public void thresholdOne_alwaysMatches() {
        // 阈值 1 总是命中
        ProbabilityCriterion criterion = new ProbabilityCriterion(BigDecimal.ONE);
        int hitCount = countHits(criterion, 100);
        Assert.assertEquals(100, hitCount);
    }

    @Test
    public void thresholdPointFive_approximatelyHalf() {
        // 阈值 0.5 大约一半概率命中
        ProbabilityCriterion criterion = new ProbabilityCriterion(new BigDecimal("0.5"));
        int totalTests = 1000;
        int hitCount = countHits(criterion, totalTests);
        // 在合理误差范围内（40%~60%）
        Assert.assertTrue("Hit rate should be around 50%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.4 && hitCount < totalTests * 0.6);
    }

    @Test
    public void thresholdPointThree_approximatelyThirtyPercent() {
        // 阈值 0.3 大约 30% 概率命中
        ProbabilityCriterion criterion = new ProbabilityCriterion(new BigDecimal("0.3"));
        int totalTests = 1000;
        int hitCount = countHits(criterion, totalTests);
        // 在合理误差范围内（20%~40%）
        Assert.assertTrue("Hit rate should be around 30%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.2 && hitCount < totalTests * 0.4);
    }

    @Test
    public void thresholdPointSeven_approximatelySeventyPercent() {
        // 阈值 0.7 大约 70% 概率命中
        ProbabilityCriterion criterion = new ProbabilityCriterion(new BigDecimal("0.7"));
        int totalTests = 1000;
        int hitCount = countHits(criterion, totalTests);
        // 在合理误差范围内（60%~80%）
        Assert.assertTrue("Hit rate should be around 70%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.6 && hitCount < totalTests * 0.8);
    }

    // ==================== 特殊阈值测试 ====================

    @Test
    public void thresholdPointOne_approximatelyTenPercent() {
        // 阈值 0.1 大约 10% 概率命中
        ProbabilityCriterion criterion = new ProbabilityCriterion(new BigDecimal("0.1"));
        int totalTests = 1000;
        int hitCount = countHits(criterion, totalTests);
        // 在合理误差范围内（5%~15%）
        Assert.assertTrue("Hit rate should be around 10%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.05 && hitCount < totalTests * 0.15);
    }

    @Test
    public void thresholdPointNine_approximatelyNinetyPercent() {
        // 阈值 0.9 大约 90% 概率命中
        ProbabilityCriterion criterion = new ProbabilityCriterion(new BigDecimal("0.9"));
        int totalTests = 1000;
        int hitCount = countHits(criterion, totalTests);
        // 在合理误差范围内（85%~100%）
        Assert.assertTrue("Hit rate should be around 90%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.85);
    }

    // ==================== actual 值不影响结果测试 ====================

    @Test
    public void actualValueDoesNotAffectProbability() {
        // actual 值不应该影响概率判断
        ProbabilityCriterion criterion = new ProbabilityCriterion(new BigDecimal("0.5"));
        int totalTests = 100;

        int hitCount = countHits(criterion, "any string", totalTests);
        // 验证概率分布合理
        Assert.assertTrue("Hit rate should be around 50%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.3 && hitCount < totalTests * 0.7);

        // 再次测试，使用不同的 actual 值
        hitCount = countHits(criterion, 12345, totalTests);
        Assert.assertTrue("Hit rate should be around 50%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.3 && hitCount < totalTests * 0.7);
    }

    @Test
    public void nullActual_stillMatchesBasedOnProbability() {
        // null 值不应该影响概率判断
        ProbabilityCriterion criterion = new ProbabilityCriterion(new BigDecimal("0.5"));
        int totalTests = 100;
        int hitCount = countHits(criterion, null, totalTests);
        // 验证概率分布合理
        Assert.assertTrue("Hit rate should be around 50%, actual: " + hitCount + "/" + totalTests,
                hitCount > totalTests * 0.3 && hitCount < totalTests * 0.7);
    }
}
