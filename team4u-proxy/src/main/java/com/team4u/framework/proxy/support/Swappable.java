package com.team4u.framework.proxy.support;

/**
 * 动态可热交换契约
 *
 * @author team4u
 */
public interface Swappable {
    /**
     * 替换底层的真实委托对象
     *
     * @param newDelegate 新的委托对象
     * @return 替换下来的旧委托对象
     */
    Object hotswap(Object newDelegate);
}
