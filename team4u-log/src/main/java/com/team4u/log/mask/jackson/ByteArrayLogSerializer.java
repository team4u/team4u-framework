package com.team4u.log.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * 字节数组防爆序列化器
 * <p>
 * 防止将大文件（byte[]）序列化为巨大的 Base64 字符串。
 */
public class ByteArrayLogSerializer extends StdSerializer<byte[]> {

    public ByteArrayLogSerializer() {
        super(byte[].class);
    }

    @Override
    public void serialize(byte[] value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        // 遇到 byte[] 直接输出大小，不进行 Base64 编码
        gen.writeString("[byte[] size: " + value.length + " bytes]");
    }
}
