package com.team4u.framework.retry.proxy.serialize;

import cn.hutool.core.lang.TypeReference;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Type;
import java.util.List;

public class HutoolRetryContextSerializerTest {

    private final HutoolRetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

    @Test
    public void testSerializeNullReturnsNull() {
        Assert.assertNull(serializer.serialize(null));
    }

    @Test
    public void testDeserializePrimitiveWrapperAndChar() {
        Assert.assertEquals(7, serializer.deserialize(int.class, "7"));
        Assert.assertEquals(Boolean.TRUE, serializer.deserialize(Boolean.class, "true"));
        Assert.assertEquals(Character.valueOf('A'), serializer.deserialize(Character.class, "\"A\""));
    }

    @Test
    public void testDeserializeEnum() {
        Assert.assertEquals(Level.HIGH, serializer.deserialize(Level.class, "\"HIGH\""));
    }

    @Test
    public void testSerializeEnumUsesName() {
        Assert.assertEquals("HIGH", serializer.serialize(Level.HIGH));
    }

    @Test
    public void testDeserializeParameterizedCollection() {
        Type listType = new TypeReference<List<Item>>() { }.getType();

        @SuppressWarnings("unchecked")
        List<Item> items = (List<Item>) serializer.deserialize(listType,
                "[{\"value\":\"x\"},{\"value\":\"y\"}]");

        Assert.assertEquals(2, items.size());
        Assert.assertEquals("x", items.get(0).getValue());
        Assert.assertEquals("y", items.get(1).getValue());
    }

    @Test
    public void testDeserializeParameterizedContainer() {
        Type containerType = new TypeReference<Envelope<Item>>() { }.getType();

        @SuppressWarnings("unchecked")
        Envelope<Item> envelope = (Envelope<Item>) serializer.deserialize(containerType,
                "{\"payload\":{\"value\":\"z\"}}");

        Assert.assertNotNull(envelope.getPayload());
        Assert.assertEquals("z", envelope.getPayload().getValue());
    }

    private enum Level {
        HIGH
    }

    public static class Item {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class Envelope<T> {
        private T payload;

        public T getPayload() {
            return payload;
        }

        public void setPayload(T payload) {
            this.payload = payload;
        }
    }
}
