package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.definition.type.TypeCodec;
import com.team4u.framework.flow.definition.type.TypeCodecs;
import com.team4u.framework.flow.definition.type.TypeRef;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;

public class TypeCodecsTest {

    enum Status {
        PAID,
        CANCELLED,
        PENDING
    }

    @Test
    public void testStringCodec() {
        TypeCodec<String> codec = TypeCodecs.STRING;
        Assert.assertEquals("hello", codec.decode("\"hello\""));
        Assert.assertEquals("world", codec.decode("world"));
        Assert.assertEquals("\"test\"", codec.encode("test"));
    }

    @Test
    public void testBooleanCodec() {
        TypeCodec<Boolean> codec = TypeCodecs.BOOLEAN;
        Assert.assertEquals(Boolean.TRUE, codec.decode("true"));
        Assert.assertEquals(Boolean.FALSE, codec.decode("false"));
        Assert.assertEquals("true", codec.encode(true));
    }

    @Test
    public void testNumberCodecs() {
        Assert.assertEquals(Integer.valueOf(123), TypeCodecs.INTEGER.decode("123"));
        Assert.assertEquals(Long.valueOf(456), TypeCodecs.LONG.decode("456L"));
        Assert.assertEquals(Double.valueOf(3.14), TypeCodecs.DOUBLE.decode("3.14"));
    }

    @Test
    public void testEnumCodec() {
        TypeCodec<Status> codec = TypeCodecs.forEnum(Status.class);
        Assert.assertEquals(Status.PAID, codec.decode("PAID"));
        Assert.assertEquals(Status.PAID, codec.decode("paid"));
        Assert.assertEquals(Status.CANCELLED, codec.decode("\"CANCELLED\""));
        Assert.assertEquals("PAID", codec.encode(Status.PAID));
    }

    @Test
    public void testDurationCodec() {
        Assert.assertEquals(Duration.ofSeconds(3), TypeCodecs.parseDuration("3s"));
        Assert.assertEquals(Duration.ofMillis(500), TypeCodecs.parseDuration("500ms"));
        Assert.assertEquals(Duration.ofMinutes(2), TypeCodecs.parseDuration("2m"));
        Assert.assertEquals(Duration.ofHours(1), TypeCodecs.parseDuration("1h"));
        Assert.assertEquals(Duration.ofDays(1), TypeCodecs.parseDuration("1d"));
        Assert.assertEquals(Duration.ofNanos(100), TypeCodecs.parseDuration("100ns"));
        Assert.assertEquals(Duration.ofSeconds(10), TypeCodecs.parseDuration("PT10S"));
    }

    @Test
    public void testForTypeLookup() {
        Assert.assertSame(TypeCodecs.STRING, TypeCodecs.forType(TypeRef.of(String.class)));
        Assert.assertSame(TypeCodecs.INTEGER, TypeCodecs.forType(TypeRef.of(Integer.class)));
        Assert.assertNotNull(TypeCodecs.forType(TypeRef.of(Status.class)));
        Assert.assertNull(TypeCodecs.forType(TypeRef.of(Object.class)));
        Assert.assertNull(TypeCodecs.forType(TypeRef.of(TypeCodecsTest.class)));
        Assert.assertNull(TypeCodecs.forType(TypeRef.ANY));
        Assert.assertNull(TypeCodecs.forType(null));
    }
}
