package com.team4u.framework.kv.lock;

/**
 * 阻塞获取锁超时
 *
 * @author jay.wu
 */
public class KvLockTimeoutException extends Exception {

    public KvLockTimeoutException(String message) {
        super(message);
    }
}
