package com.team4u.framework.flow;

/**
 * 流程事件监听器。Observer 只接收不可变事件，不能修改输入、输出或执行结果。
 * Observer 抛出的运行时异常会被框架隔离并忽略，不改变流程结果。
 *
 * @author jay.wu
 */
@FunctionalInterface
public interface FlowObserver {

    /**
     * 接收流程执行事件。
     *
     * @param event 不可变流程事件
     */
    void onEvent(FlowEvent event);
}
