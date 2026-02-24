package com.team4u.criterion.model;

import lombok.Getter;
import com.team4u.criterion.model.value.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 集合全集包含规则。
 * <p>
 * 用于判断属性（集合或数组）是否完全包含指定值（集合或数组）。
 * 支持静态值和动态变量：
 * <ul>
 * <li>userTags containsAll ['VIP', 'KOL'] - 静态引用的集合。</li>
 * <li>userTags containsAll requiredTags - 动态变量。</li>
 * </ul>
 */
@Getter
public class ContainsAllCriterion extends Criterion implements ValueContainer {

    /**
     * 待比较的值。
     */
    private final List<Value<?>> values;

    /**
     * 构建全集包含规则。
     *
     * @param targetValues 值列表
     */
    public ContainsAllCriterion(List<? extends Value<?>> targetValues) {
        this.values = new ArrayList<>(targetValues);
    }

    @Override
    public List<Value<?>> values() {
        return values;
    }
}
