package com.team4u.framework.lease;

import com.team4u.framework.lease.api.Task;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class TaskTest {

    @Test
    public void testDefaultsAndImmutableAttributes() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("traceId", "trace-1");
        source.put("region", "cn");

        Task task = Task.of("email.send", "{}")
                .deduplicationKey("order-1")
                .delay(java.time.Duration.ofSeconds(2))
                .priority(8)
                .attributes(source);

        source.put("traceId", "changed");

        Assert.assertEquals("email.send", task.getType());
        Assert.assertEquals("{}", task.getPayload());
        Assert.assertEquals("order-1", task.getDeduplicationKey());
        Assert.assertEquals(java.time.Duration.ofSeconds(2), task.getDelay());
        Assert.assertEquals(8, task.getPriority());
        Assert.assertEquals("trace-1", task.getAttributes().get("traceId"));
        Assert.assertEquals(2, task.getAttributes().size());

        try {
            task.getAttributes().put("region", "us");
            Assert.fail("expected immutable attributes");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void testDefaults() {
        Task task = Task.of("email.send", "{}");

        Assert.assertNull(task.getDeduplicationKey());
        Assert.assertEquals(java.time.Duration.ZERO, task.getDelay());
        Assert.assertEquals(0, task.getPriority());
        Assert.assertTrue(task.getAttributes().isEmpty());
    }

    @Test
    public void testRejectsInvalidType() {
        assertInvalidType(null);
        assertInvalidType("");
        assertInvalidType(" ");
    }

    @Test
    public void testRejectsInvalidValues() {
        try {
            Task.of("email.send", "{}").delay(java.time.Duration.ofMillis(-1));
            Assert.fail("expected negative delay to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("delay"));
        }

        try {
            Task.of("email.send", "{}").priority(-1);
            Assert.fail("expected negative priority to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("priority"));
        }

        try {
            Task.of("email.send", "{}").deduplicationKey(" ");
            Assert.fail("expected blank deduplication key to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("deduplicationKey"));
        }
    }

    @Test
    public void testRejectsInvalidAttribute() {
        assertInvalidAttribute(null, "value");
        assertInvalidAttribute("key", null);
    }

    private void assertInvalidType(String type) {
        try {
            Task.of(type, "{}");
            Assert.fail("expected invalid type to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("type"));
        }
    }

    private void assertInvalidAttribute(String key, String value) {
        try {
            Task.of("email.send", "{}").attribute(key, value);
            Assert.fail("expected invalid attribute to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("attribute"));
        }
    }
}
