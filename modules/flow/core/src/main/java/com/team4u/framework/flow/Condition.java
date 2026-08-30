package com.team4u.framework.flow;

/**
 * 守卫条件合同：判定当前值是否满足继续执行的条件。
 *
 * @param <I> 输入类型
 * @author jay.wu
 */
@FunctionalInterface
public interface Condition<I> {

    /**
     * 判定条件是否成立。
     *
     * @param input 当前值，非 null
     * @return true 表示条件通过，false 表示条件不满足（将触发业务停止）
     * @throws Exception 判定异常
     */
    boolean test(I input) throws Exception;
}
