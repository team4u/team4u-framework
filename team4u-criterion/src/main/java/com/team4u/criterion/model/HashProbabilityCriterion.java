package com.team4u.criterion.model;

import lombok.Getter;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.Value;

import java.util.Collections;
import java.util.List;

/**
 * Hash 概率分流规则
 * <p>
 * 通过对输入值计算 Hash 并映射到 [0, 1] 区间，实现确定性的概率分流。
 * 相同的输入值始终获得相同的匹配结果。
 * <p>
 * 支持静态值和动态变量：
 * <ul>
 * <li>userId hash 0.3 - 30% 的用户命中（静态值）</li>
 * <li>userId hash $experimentRate - 动态变量</li>
 * </ul>
 *
 * @author jay.wu
 */
@Getter
public class HashProbabilityCriterion extends Criterion implements ValueContainer {
    /**
     * 命中阈值 (0.0 ~ 1.0)
     */
    private final Value<Number> threshold;

    public HashProbabilityCriterion(Value<Number> threshold) {
        this.threshold = threshold;
    }

    /**
     * 便捷构造方法（兼容静态值用法）
     */
    public HashProbabilityCriterion(Number threshold) {
        this(new FixedValue<>(threshold));
    }

    @Override
    public List<Value<?>> values() {
        return Collections.singletonList(threshold);
    }
}