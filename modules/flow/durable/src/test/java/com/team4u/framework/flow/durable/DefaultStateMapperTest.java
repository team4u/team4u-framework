package com.team4u.framework.flow.durable;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

/**
 * {@link DefaultStateMapper} 单元测试：
 * 验证对原生基础类型的确定性支持，以及对需要自定义 StateMapper 的领域对象的明确拒绝。
 *
 * @author jay.wu
 */
public class DefaultStateMapperTest {

    private final DefaultStateMapper mapper = DefaultStateMapper.INSTANCE;

    @Test
    public void testNullRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode(null);
        Assert.assertEquals("null", encoded.typeId());
        Assert.assertEquals(0, encoded.data().length);

        Object decoded = mapper.decode(encoded);
        Assert.assertNull(decoded);
    }

    @Test
    public void testStringRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode("hello world");
        Assert.assertEquals("string", encoded.typeId());
        Assert.assertEquals("hello world", encoded.asString());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals("hello world", decoded);
    }

    @Test
    public void testIntegerRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode(12345);
        Assert.assertEquals("int", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals(12345, decoded);
    }

    @Test
    public void testLongRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode(9876543210123L);
        Assert.assertEquals("long", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals(9876543210123L, decoded);
    }

    @Test
    public void testBooleanRoundTrip() throws Exception {
        StoredValue encTrue = mapper.encode(true);
        Assert.assertEquals("bool", encTrue.typeId());
        Assert.assertEquals(true, mapper.decode(encTrue));

        StoredValue encFalse = mapper.encode(false);
        Assert.assertEquals("bool", encFalse.typeId());
        Assert.assertEquals(false, mapper.decode(encFalse));
    }

    @Test
    public void testDoubleRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode(3.1415926);
        Assert.assertEquals("double", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals(3.1415926, (Double) decoded, 0.0000001);
    }

    @Test
    public void testFloatRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode(2.71828f);
        Assert.assertEquals("float", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals(2.71828f, (Float) decoded, 0.00001f);
    }

    @Test
    public void testShortRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode((short) 42);
        Assert.assertEquals("short", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals((short) 42, decoded);
    }

    @Test
    public void testByteRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode((byte) 7);
        Assert.assertEquals("byte", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals((byte) 7, decoded);
    }

    @Test
    public void testCharacterRoundTrip() throws Exception {
        StoredValue encoded = mapper.encode('Z');
        Assert.assertEquals("char", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertEquals('Z', decoded);
    }

    @Test
    public void testBytesRoundTrip() throws Exception {
        byte[] bytes = new byte[]{10, 20, 30, 40};
        StoredValue encoded = mapper.encode(bytes);
        Assert.assertEquals("bytes", encoded.typeId());

        Object decoded = mapper.decode(encoded);
        Assert.assertTrue(decoded instanceof byte[]);
        Assert.assertArrayEquals(bytes, (byte[]) decoded);
    }

    @Test
    public void testDomainObjectsFailClearlyOnEncode() {
        // Custom domain class
        class CustomDomainObject {}

        try {
            mapper.encode(new CustomDomainObject());
            Assert.fail("Expected IllegalArgumentException for unsupported domain object");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Cannot encode unsupported type"));
            Assert.assertTrue(e.getMessage().contains("Implement custom StateMapper for domain types"));
        } catch (Exception e) {
            Assert.fail("Expected IllegalArgumentException, got: " + e);
        }

        // Standard library complex / serializable objects
        try {
            mapper.encode(new Date());
            Assert.fail("Expected IllegalArgumentException for Date");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Cannot encode unsupported type"));
        } catch (Exception e) {
            Assert.fail("Expected IllegalArgumentException, got: " + e);
        }

        try {
            mapper.encode(new ArrayList<String>());
            Assert.fail("Expected IllegalArgumentException for ArrayList");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Cannot encode unsupported type"));
        } catch (Exception e) {
            Assert.fail("Expected IllegalArgumentException, got: " + e);
        }

        try {
            mapper.encode(new HashMap<String, Object>());
            Assert.fail("Expected IllegalArgumentException for HashMap");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Cannot encode unsupported type"));
        } catch (Exception e) {
            Assert.fail("Expected IllegalArgumentException, got: " + e);
        }
    }

    @Test
    public void testDecodeUnknownOrLegacyTypeIdThrows() {
        try {
            mapper.decode(new StoredValue("java-serializable", new byte[]{1, 2, 3}));
            Assert.fail("Expected IllegalArgumentException for legacy java-serializable typeId");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Unknown or unsupported typeId"));
            Assert.assertTrue(e.getMessage().contains("java-serializable"));
        } catch (Exception e) {
            Assert.fail("Expected IllegalArgumentException, got: " + e);
        }

        try {
            mapper.decode(new StoredValue("com.example.Order", "{}".getBytes(StandardCharsets.UTF_8)));
            Assert.fail("Expected IllegalArgumentException for custom typeId");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Unknown or unsupported typeId"));
        } catch (Exception e) {
            Assert.fail("Expected IllegalArgumentException, got: " + e);
        }
    }

    @Test
    public void testDecodeMalformedBooleanThrows() {
        String[] malformed = new String[]{"1", "0", "TRUE", "FALSE", "True", "False", "foo", "", "true1", "false "};
        for (String val : malformed) {
            try {
                mapper.decode(new StoredValue("bool", val.getBytes(StandardCharsets.UTF_8)));
                Assert.fail("Expected IllegalArgumentException for malformed boolean: " + val);
            } catch (IllegalArgumentException e) {
                Assert.assertTrue(e.getMessage().contains("Invalid boolean data"));
            } catch (Exception e) {
                Assert.fail("Expected IllegalArgumentException for malformed boolean: " + val + ", got: " + e);
            }
        }
    }

    @Test
    public void testDecodeMalformedCharThrows() {
        String[] malformed = new String[]{"", "AB", "xyz"};
        for (String val : malformed) {
            try {
                mapper.decode(new StoredValue("char", val.getBytes(StandardCharsets.UTF_8)));
                Assert.fail("Expected IllegalArgumentException for malformed char: " + val);
            } catch (IllegalArgumentException e) {
                Assert.assertTrue(e.getMessage().contains("Invalid char data"));
            } catch (Exception e) {
                Assert.fail("Expected IllegalArgumentException for malformed char: " + val + ", got: " + e);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeNullStoredValueThrows() throws Exception {
        mapper.decode(null);
    }
}
