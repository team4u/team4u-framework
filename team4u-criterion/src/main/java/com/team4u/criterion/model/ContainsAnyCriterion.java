package com.team4u.criterion.model;

import lombok.Getter;
import com.team4u.criterion.model.value.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 集合交集检查规则。
 * <p>
 * 用于判断属性（集合或数组）与指定值（集合或数组）是否存在交集。
 * 支持静态值和动态变量：
 * <ul>
 * <li>userTags containsAny ['VIP', 'KOL'] - 静态引用的集合。</li>
 * <li>userTags containsAny requiredTags - 动态变量。</li>
 * </ul>
 */
@Getter
public class ContainsAnyCriterion extends Criterion implements ValueContainer {

    /**
     * 待比较的值。
     */
    private final List<Value<?>> values;

    /**
     * 构建交集规则。
     *
     * @param targetValues 值列表
     */
    public ContainsAnyCriterion(List<? extends Value<?>> targetValues) {
        this.values = new ArrayList<>(targetValues);
    }

    @Override
    public List<Value<?>> values() {
        return values;
    }
}