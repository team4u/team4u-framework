package com.team4u.framework.base.util;

/**
 * 对象拷贝配置
 * <p>
 * 用于在 Bean 属性复制或 Map 转换过程中配置行为参数，如是否忽略字段大小写、是否忽略转换过程中的错误。
 *
 * @author jay.wu
 */
public class CopyOptions {
    /**
     * 是否忽略字段名称大小写
     */
    private boolean ignoreCase;
    /**
     * 是否忽略转换过程中的异常错误
     */
    private boolean ignoreError;

    /**
     * 创建默认的拷贝配置实例
     *
     * @return 新的 CopyOptions 实例
     */
    public static CopyOptions create() {
        return new CopyOptions();
    }

    /**
     * 设置为忽略字段名大小写
     *
     * @return 当前配置实例
     */
    public CopyOptions ignoreCase() {
        this.ignoreCase = true;
        return this;
    }

    /**
     * 设置为忽略转换过程中的错误
     *
     * @return 当前配置实例
     */
    public CopyOptions ignoreError() {
        this.ignoreError = true;
        return this;
    }

    /**
     * 获取是否忽略大小写的配置值
     *
     * @return 忽略则返回 true，否则返回 false
     */
    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    /**
     * 获取是否忽略错误的配置值
     *
     * @return 忽略则返回 true，否则返回 false
     */
    public boolean isIgnoreError() {
        return ignoreError;
    }
}
