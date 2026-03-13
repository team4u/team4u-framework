package com.team4u.framework.base.util;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.Closeable;
import java.io.IOException;

/**
 * IoUtil 单元测试
 *
 * @author jay.wu
 */
public class IoUtilTest {

    @Test
    public void close() throws IOException {
        // 测试传入 null 时不抛出异常
        IoUtil.close(null);

        // 测试正常关闭流
        Closeable closeable = Mockito.mock(Closeable.class);
        IoUtil.close(closeable);
        Mockito.verify(closeable).close();

        // 测试关闭流抛出 IOException 时被正确忽略
        Closeable exceptionCloseable = Mockito.mock(Closeable.class);
        Mockito.doThrow(new IOException("关闭失败")).when(exceptionCloseable).close();
        IoUtil.close(exceptionCloseable);
        Mockito.verify(exceptionCloseable).close();
    }
}
