package com.team4u.framework.kv;

/**
 * 写入模式
 */
public enum PutMode {

    /**
     * 覆盖写：无论键是否存在均写入
     */
    SET,

    /**
     * 仅当键不存在时写入（SETNX 语义），键已存在时写入失败
     */
    IF_ABSENT
}
