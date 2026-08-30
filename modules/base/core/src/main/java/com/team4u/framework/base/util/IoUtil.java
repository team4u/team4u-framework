package com.team4u.framework.base.util;

import java.io.Closeable;
import java.io.IOException;

/**
 * IO 工具类
 * <p>
 * 提供输入输出流相关的常用操作，目前包含资源的安全关闭。
 *
 * @author jay.wu
 */
public class IoUtil {

    /**
     * 关闭可关闭的对象
     * <p>
     * 自动检查对象是否为 null。如果在关闭过程中抛出 {@link IOException}，则会被忽略。
     *
     * @param closeable 实现了 {@link Closeable} 接口的对象，如流、连接等。若为 null 则不做任何操作。
     */
    public static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 忽略关闭时的异常
            }
        }
    }
}
