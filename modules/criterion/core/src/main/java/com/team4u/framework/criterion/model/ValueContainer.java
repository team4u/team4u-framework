package com.team4u.framework.criterion.model;

import com.team4u.framework.criterion.model.value.Value;

import java.util.List;

/**
 * 值容器接口
 * <p>
 * 标识该对象持有一个或多个 Value 对象，用于统一提取变量
 *
 * @author jay.wu
 */
public interface ValueContainer {

    /**
     * 获取持有的所有 Value 对象
     *
     * @return Value 对象列表
     */
    List<Value<?>> values();
}
