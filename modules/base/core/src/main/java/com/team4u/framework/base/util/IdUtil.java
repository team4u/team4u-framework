package com.team4u.framework.base.util;

import java.util.UUID;

/**
 * ID 生成工具类
 * <p>
 * 提供各种唯一标识符（ID）的生成功能，目前包含简化 UUID 的生成。
 *
 * @author jay.wu
 */
public class IdUtil {

    /**
     * 生成简化的 UUID 字符串
     * <p>
     * 生成一个随机的 UUID，并移除其中的连字符（-），得到一个 32 位的唯一字符串。
     *
     * @return 移除连字符后的 32 位 UUID 字符串
     */
    public static String simpleUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
