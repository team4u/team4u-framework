package com.team4u.framework.id.api;

/**
 * 序号耗尽异常
 * <p>
 * 规则设置了 {@code maxValue} 且未开启循环，可用序号已全部发出。
 * 额度类场景应使用 {@link Sequences#tryNext(String)} 以 {@code null} 判定耗尽。
 * </p>
 *
 * @author jay.wu
 */
public class SeqExhaustedException extends SeqException {

    public SeqExhaustedException(String message) {
        super(message);
    }
}
