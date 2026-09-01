package com.team4u.framework.flow.definition.registry;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.function.Function;

/**
 * 策略绑定模型（Policy Binding），持有策略实例或契约 Class 以及动态提取 key 的投影函数。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class PolicyBinding {

    private final Object instance;
    private final Class<?> contract;
    private final String qualifier;
    private final Function<Object, Object> keyProjection;
    private final boolean persistent;

    @Builder(toBuilder = true)
    public PolicyBinding(
            Object instance,
            Class<?> contract,
            String qualifier,
            Function<Object, Object> keyProjection,
            boolean persistent) {
        this.instance = instance;
        this.contract = contract != null
                ? contract
                : (instance != null ? instance.getClass() : null);
        this.qualifier = qualifier;
        this.keyProjection = keyProjection;
        this.persistent = persistent;
    }
}
