package com.team4u.framework.parser;

import org.junit.Assert;
import org.junit.Test;

import java.io.*;

public class SourceSpanTest {

    @Test
    public void testUnknown() {
        SourceSpan span = SourceSpan.UNKNOWN;
        Assert.assertNull(span.source());
        Assert.assertEquals(-1, span.startOffset());
        Assert.assertEquals(-1, span.startLine());
        Assert.assertEquals(-1, span.startColumn());
        Assert.assertEquals(-1, span.endOffset());
        Assert.assertEquals(-1, span.endLine());
        Assert.assertEquals(-1, span.endColumn());
        Assert.assertFalse(span.known());
        Assert.assertEquals("<unknown>", span.format());
        Assert.assertEquals("<unknown>", span.toString());
    }

    @Test
    public void testSingleCharacter() {
        SourceSpan span = new SourceSpan("test.dsl", 1, 1, 2, 2, 1, 3);
        Assert.assertEquals("test.dsl", span.source());
        Assert.assertEquals(1, span.startOffset());
        Assert.assertEquals(1, span.startLine());
        Assert.assertEquals(2, span.startColumn());
        Assert.assertEquals(2, span.endOffset());
        Assert.assertEquals(1, span.endLine());
        Assert.assertEquals(3, span.endColumn());
        Assert.assertTrue(span.known());
        Assert.assertEquals("test.dsl:1:2", span.format());
    }

    @Test
    public void testSingleLine() {
        SourceSpan span = new SourceSpan("main.flow", 0, 1, 1, 5, 1, 6);
        Assert.assertTrue(span.known());
        Assert.assertEquals("main.flow:1:1", span.format());
    }

    @Test
    public void testMultiLine() {
        SourceSpan span = new SourceSpan("order.flow", 10, 2, 5, 45, 5, 12);
        Assert.assertTrue(span.known());
        Assert.assertEquals(10, span.startOffset());
        Assert.assertEquals(2, span.startLine());
        Assert.assertEquals(5, span.startColumn());
        Assert.assertEquals(45, span.endOffset());
        Assert.assertEquals(5, span.endLine());
        Assert.assertEquals(12, span.endColumn());
        Assert.assertEquals("order.flow:2:5", span.format());
    }

    @Test
    public void testExclusiveEnd() {
        String source = "abcdef";
        SourceSpan span = new SourceSpan(null, 1, 1, 2, 4, 1, 5);
        Assert.assertEquals("bcd", source.substring(span.startOffset(), span.endOffset()));
        Assert.assertEquals("1:2", span.format());
    }

    @Test
    public void testNullSourceName() {
        SourceSpan span = new SourceSpan(null, 0, 1, 1, 3, 1, 4);
        Assert.assertNull(span.source());
        Assert.assertEquals("1:1", span.format());

        SourceSpan unknownWithSource = new SourceSpan("virtual.dsl", -1, -1, -1, -1, -1, -1);
        Assert.assertFalse(unknownWithSource.known());
        Assert.assertEquals("virtual.dsl", unknownWithSource.format());
    }

    @Test
    public void testEqualsAndHashCode() {
        SourceSpan s1 = new SourceSpan("a.flow", 0, 1, 1, 5, 1, 6);
        SourceSpan s2 = new SourceSpan("a.flow", 0, 1, 1, 5, 1, 6);
        SourceSpan s3 = new SourceSpan("b.flow", 0, 1, 1, 5, 1, 6);
        SourceSpan s4 = new SourceSpan("a.flow", 1, 1, 1, 5, 1, 6);

        Assert.assertEquals(s1, s2);
        Assert.assertEquals(s1.hashCode(), s2.hashCode());
        Assert.assertNotEquals(s1, s3);
        Assert.assertNotEquals(s1, s4);
        Assert.assertNotEquals(s1, null);
        Assert.assertNotEquals(s1, "other");
    }

    @Test
    public void testSerialization() throws Exception {
        SourceSpan original = new SourceSpan("demo.flow", 12, 3, 4, 25, 4, 8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(original);
        oos.close();

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
        SourceSpan deserialized = (SourceSpan) ois.readObject();
        Assert.assertEquals(original, deserialized);
    }
}
