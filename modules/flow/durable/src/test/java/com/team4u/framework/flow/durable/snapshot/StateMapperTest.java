package com.team4u.framework.flow.durable.snapshot;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.Assert.*;

/**
 * 状态映射器与复合序列化映射器单元测试。
 *
 * @author jay.wu
 */
public class StateMapperTest {

    @Test
    public void testDefaultStateMapperScalars() throws Exception {
        DefaultStateMapper mapper = DefaultStateMapper.INSTANCE;

        // String
        StoredValue vStr = mapper.encode("hello");
        assertEquals("team4u-default:string", vStr.codecId());
        assertEquals("hello", mapper.decode(vStr));

        // Int, Long, Boolean, Double, Float, Short, Byte, Char
        assertEquals(123, mapper.decode(mapper.encode(123)));
        assertEquals(123456789L, mapper.decode(mapper.encode(123456789L)));
        assertEquals(Boolean.TRUE, mapper.decode(mapper.encode(true)));
        assertEquals(Boolean.FALSE, mapper.decode(mapper.encode(false)));
        assertEquals(3.14, (Double) mapper.decode(mapper.encode(3.14)), 0.0001);
        assertEquals(1.5f, (Float) mapper.decode(mapper.encode(1.5f)), 0.0001);
        assertEquals((short) 42, mapper.decode(mapper.encode((short) 42)));
        assertEquals((byte) 7, mapper.decode(mapper.encode((byte) 7)));
        assertEquals('A', mapper.decode(mapper.encode('A')));

        // Bytes
        byte[] bytes = new byte[]{1, 2, 3};
        assertArrayEquals(bytes, (byte[]) mapper.decode(mapper.encode(bytes)));

        // Instant
        Instant now = Instant.now();
        assertEquals(now, mapper.decode(mapper.encode(now)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultStateMapperUnsupportedType() throws Exception {
        DefaultStateMapper.INSTANCE.encode(new Object());
    }

    @Test
    public void testSerializerStateMapper() throws Exception {
        SerializerStateMapper customMapper = new SerializerStateMapper("custom:csv", 1,
                val -> ((String) val).getBytes(StandardCharsets.UTF_8),
                bytes -> new String(bytes, StandardCharsets.UTF_8));

        StoredValue stored = customMapper.encode("a,b,c");
        assertEquals("custom:csv", stored.codecId());
        assertEquals("a,b,c", customMapper.decode(stored));
    }

    @Test
    public void testCompositeStateMapperFallback() throws Exception {
        // 自定义 POJO 模拟器
        SerializerStateMapper pojoMapper = new SerializerStateMapper("mock:json", 1,
                val -> ("JSON:" + val.toString()).getBytes(StandardCharsets.UTF_8),
                bytes -> new String(bytes, StandardCharsets.UTF_8));

        CompositeStateMapper composite = CompositeStateMapper.withDefault(pojoMapper);

        // 标量走 DefaultStateMapper
        StoredValue scalarVal = composite.encode("my-scalar");
        assertEquals("team4u-default:string", scalarVal.codecId());
        assertEquals("my-scalar", composite.decode(scalarVal));

        // 复杂类型回退到 pojoMapper
        StringBuilder complexObj = new StringBuilder("complex-data");
        StoredValue complexVal = composite.encode(complexObj);
        assertEquals("mock:json", complexVal.codecId());
        assertEquals("JSON:complex-data", composite.decode(complexVal));
    }
}
