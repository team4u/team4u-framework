package com.team4u.framework.mask.policy;

import com.team4u.framework.mask.MaskPolicy;

/**
 * 带固定 key 的脱敏策略基类
 * <p>
 * 为参数化策略（构造时才能确定 key）提供 key 载体，
 * 与既有「一策略一 key」实现保持同一注册模型。
 *
 * @author jay.wu
 */
public abstract class AbstractKeyedMaskPolicy implements MaskPolicy {

    private final String key;

    protected AbstractKeyedMaskPolicy(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
