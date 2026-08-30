package com.team4u.framework.lease;

import com.team4u.framework.lease.spi.TaskSubscription;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class TaskSubscriptionTest {

    @Test
    public void testCreatesExactSubscription() {
        Set<String> source = new LinkedHashSet<String>();
        source.add("email.send");
        source.add("report.build");

        TaskSubscription subscription = TaskSubscription.of("orders", source);
        source.add("unexpected.type");

        Assert.assertEquals("orders", subscription.getQueue());
        Assert.assertEquals(2, subscription.getTaskTypes().size());
        Assert.assertTrue(subscription.getTaskTypes().contains("email.send"));
        Assert.assertTrue(subscription.getTaskTypes().contains("report.build"));

        try {
            subscription.getTaskTypes().add("another.type");
            Assert.fail("expected immutable task types");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void testRejectsInvalidSubscription() {
        assertInvalid(null, Collections.singleton("email.send"));
        assertInvalid("", Collections.singleton("email.send"));
        assertInvalid(" ", Collections.singleton("email.send"));
        assertInvalid("orders", Collections.<String>emptySet());
        assertInvalid("orders", null);
        assertInvalid("orders", Collections.singleton(null));
        assertInvalid("orders", Collections.singleton(""));
        assertInvalid("orders", Collections.singleton("*"));
        assertInvalid("orders", Collections.singleton(">"));
    }

    private void assertInvalid(String queue, Set<String> types) {
        try {
            TaskSubscription.of(queue, types);
            Assert.fail("expected invalid subscription to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("taskTypes")
                    || expected.getMessage().contains("queue"));
        }
    }
}
