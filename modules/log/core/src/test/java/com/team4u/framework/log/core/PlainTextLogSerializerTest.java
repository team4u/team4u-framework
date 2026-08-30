package com.team4u.framework.log.core;

import org.junit.Assert;
import org.junit.Test;

public class PlainTextLogSerializerTest {

    @Test
    public void plainTextSerializationIsToStringBasedAndNeverJson() {
        LogEvent event = new LogEvent().setAction("plain-text");

        String value = new PlainTextLogSerializer().serialize(event);

        Assert.assertEquals(String.valueOf(event), value);
        Assert.assertTrue(value.contains("action=plain-text"));
        Assert.assertFalse(value.contains("\"action\":\"plain-text\""));
    }

    @Test
    public void plainTextSerializationFallsBackWhenToStringFails() {
        LogEvent event = new LogEvent() {
            @Override
            public String toString() {
                throw new IllegalStateException("boom");
            }
        };
        event.setAction("broken");

        String value = new PlainTextLogSerializer().serialize(event);

        Assert.assertNotNull(value);
        Assert.assertFalse(value.isEmpty());
    }
}
