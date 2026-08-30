package com.team4u.framework.id.api;

/**
 * 序号规则配置异常
 * <p>
 * 规则缺失、规则内容非法、存储无计数能力、分组策略缺失等配置类问题，
 * 属于程序错误，快速失败而非静默降级。
 * </p>
 *
 * @author jay.wu
 */
public class SeqConfigException extends SeqException {

    public SeqConfigException(String message) {
        super(message);
    }

    public SeqConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
