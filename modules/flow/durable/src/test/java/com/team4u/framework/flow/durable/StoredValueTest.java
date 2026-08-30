package com.team4u.framework.flow.durable;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * {@link StoredValue} 单元测试：验证防御性拷贝与基础契约。
 *
 * @author jay.wu
 */
public class StoredValueTest {

    @Test
    public void defensiveCopyOnConstructor() {
        byte[] original = new byte[]{1, 2, 3};
        StoredValue sv = new StoredValue("custom", original);

        // Mutate original array
        original[0] = 99;

        // sv.data() should remain unaffected
        Assert.assertEquals((byte) 1, sv.data()[0]);
    }

    @Test
    public void defensiveCopyOnGetter() {
        StoredValue sv = new StoredValue("custom", new byte[]{1, 2, 3});

        byte[] extracted = sv.data();
        extracted[0] = 99;

        // Internal data of sv should remain unaffected
        Assert.assertEquals((byte) 1, sv.data()[0]);
    }

    @Test
    public void ofStringAndAsString() {
        StoredValue sv = StoredValue.ofString("test-string");
        Assert.assertEquals("string", sv.typeId());
        Assert.assertEquals("test-string", sv.asString());
        Assert.assertArrayEquals("test-string".getBytes(StandardCharsets.UTF_8), sv.data());
    }

    @Test
    public void ofMethodDefensiveCopy() {
        byte[] arr = new byte[]{10, 20};
        StoredValue sv = StoredValue.of("bytes", arr);
        arr[0] = 99;
        Assert.assertEquals((byte) 10, sv.data()[0]);
    }

    @Test
    public void nullDataTreatedAsEmptyArray() {
        StoredValue sv = new StoredValue("null-data", null);
        Assert.assertNotNull(sv.data());
        Assert.assertEquals(0, sv.data().length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullTypeIdRejected() {
        new StoredValue(null, new byte[]{1});
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankTypeIdRejected() {
        new StoredValue("   ", new byte[]{1});
    }

    @Test
    public void equalsAndHashCode() {
        StoredValue sv1 = new StoredValue("typeA", new byte[]{1, 2});
        StoredValue sv2 = new StoredValue("typeA", new byte[]{1, 2});
        StoredValue sv3 = new StoredValue("typeA", new byte[]{1, 3});
        StoredValue sv4 = new StoredValue("typeB", new byte[]{1, 2});

        Assert.assertEquals(sv1, sv2);
        Assert.assertEquals(sv1.hashCode(), sv2.hashCode());
        Assert.assertNotEquals(sv1, sv3);
        Assert.assertNotEquals(sv1, sv4);
        Assert.assertNotEquals(sv1, null);
        Assert.assertNotEquals(sv1, "other");
    }

    @Test
    public void toStringContainsTypeIdAndLength() {
        StoredValue sv = new StoredValue("myType", new byte[]{1, 2, 3});
        String str = sv.toString();
        Assert.assertTrue(str.contains("myType"));
        Assert.assertTrue(str.contains("3 bytes"));
    }
}
