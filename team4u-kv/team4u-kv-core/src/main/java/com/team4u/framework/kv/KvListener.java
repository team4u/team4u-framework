package com.team4u.framework.kv;

/**
 * 键值变更监听器
 * <p>
 * 监听器异常会被实现隔离（记录日志），不影响存储操作与其他监听器。
 * </p>
 *
 * @author jay.wu
 */
@FunctionalInterface
public interface KvListener {

    /**
     * 收到变更事件
     */
    void onEvent(KvEvent event);
}
