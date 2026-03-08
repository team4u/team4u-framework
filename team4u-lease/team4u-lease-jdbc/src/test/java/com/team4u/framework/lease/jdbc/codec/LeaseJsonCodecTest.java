package com.team4u.framework.lease.jdbc.codec;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class LeaseJsonCodecTest {

    private final LeaseJsonCodec codec = new LeaseJsonCodec();

    @Test
    public void testRoundTrip() {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("queue", "pay");
        attributes.put("message", "hello \"jdbc\"");

        String json = codec.toJson(attributes);
        Map<String, String> decoded = codec.fromJson(json);

        Assert.assertEquals(attributes, decoded);
    }

    @Test
    public void testNullAndEmptyInput() {
        Assert.assertEquals("{}", codec.toJson(null));
        Assert.assertTrue(codec.fromJson(null).isEmpty());
        Assert.assertTrue(codec.fromJson("   ").isEmpty());
        Assert.assertTrue(codec.fromJson("{}").isEmpty());
    }
}
