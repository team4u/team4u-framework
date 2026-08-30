package com.team4u.framework.lease.jdbc.codec;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class LeaseJsonCodecTest {

    private final LeaseJsonCodec codec = new LeaseJsonCodec();

    @Test
    public void testRoundTripsTaskAttributes() {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("traceId", "trace-1");
        attributes.put("message", "hello \"jdbc\"");

        Assert.assertEquals(attributes, codec.fromJson(codec.toJson(attributes)));
    }

    @Test
    public void testEmptyAndMissingAttributesAreInterchangeable() {
        Assert.assertEquals("{}", codec.toJson(null));
        Assert.assertEquals("{}", codec.toJson(Collections.<String, String>emptyMap()));
        Assert.assertTrue(codec.fromJson(null).isEmpty());
        Assert.assertTrue(codec.fromJson(" ").isEmpty());
        Assert.assertTrue(codec.fromJson("{}").isEmpty());
    }
}
