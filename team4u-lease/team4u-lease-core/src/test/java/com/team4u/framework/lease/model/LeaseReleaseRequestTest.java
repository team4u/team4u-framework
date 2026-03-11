package com.team4u.framework.lease.model;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class LeaseReleaseRequestTest {

    @Test
    public void testAttributesAreDefensivelyCopiedAndImmutable() {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("traceId", "trace-1");

        LeaseReleaseRequest request = LeaseReleaseRequest.builder()
                .delayMillis(50L)
                .attributes(attributes)
                .build();

        attributes.put("traceId", "trace-2");

        Assert.assertEquals("trace-1", request.getAttributes().get("traceId"));
        try {
            request.getAttributes().put("newKey", "newValue");
            Assert.fail("expected attributes to be immutable");
        } catch (UnsupportedOperationException expected) {
            Assert.assertEquals(1, request.getAttributes().size());
        }
    }
}
