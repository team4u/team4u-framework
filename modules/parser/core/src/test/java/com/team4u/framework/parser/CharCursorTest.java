package com.team4u.framework.parser;

import org.junit.Assert;
import org.junit.Test;

import java.util.NoSuchElementException;

public class CharCursorTest {

    @Test
    public void testEmpty() {
        CharCursor cursor = new CharCursor("", "empty.txt");
        Assert.assertFalse(cursor.hasNext());
        Assert.assertFalse(cursor.has(0));
        Assert.assertEquals(0, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(1, cursor.column());
        Assert.assertEquals("empty.txt", cursor.sourceName());

        CharCursor nullCursor = new CharCursor(null);
        Assert.assertFalse(nullCursor.hasNext());
    }

    @Test(expected = NoSuchElementException.class)
    public void testPeekOnEmptyThrows() {
        CharCursor cursor = new CharCursor("");
        cursor.peek();
    }

    @Test(expected = NoSuchElementException.class)
    public void testAdvanceOnEmptyThrows() {
        CharCursor cursor = new CharCursor("");
        cursor.advance();
    }

    @Test
    public void testPeekAndLookahead() {
        CharCursor cursor = new CharCursor("abc");
        Assert.assertTrue(cursor.hasNext());
        Assert.assertTrue(cursor.has(0));
        Assert.assertTrue(cursor.has(1));
        Assert.assertTrue(cursor.has(2));
        Assert.assertFalse(cursor.has(3));
        Assert.assertFalse(cursor.has(-1));

        Assert.assertEquals('a', cursor.peek());
        Assert.assertEquals('a', cursor.peek(0));
        Assert.assertEquals('b', cursor.peek(1));
        Assert.assertEquals('c', cursor.peek(2));

        Assert.assertEquals(0, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(1, cursor.column());
    }

    @Test
    public void testAdvance() {
        CharCursor cursor = new CharCursor("ab", "test.txt");
        Assert.assertEquals('a', cursor.advance());
        Assert.assertEquals(1, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(2, cursor.column());

        Assert.assertEquals('b', cursor.advance());
        Assert.assertEquals(2, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(3, cursor.column());

        Assert.assertFalse(cursor.hasNext());
    }

    @Test
    public void testMarkAndSpan() {
        CharCursor cursor = new CharCursor("hello world", "test.txt");
        CharCursor.Mark start = cursor.mark();
        Assert.assertEquals(0, start.offset());
        Assert.assertEquals(1, start.line());
        Assert.assertEquals(1, start.column());

        for (int i = 0; i < 5; i++) {
            cursor.advance();
        }

        SourceSpan span = cursor.spanFrom(start);
        Assert.assertEquals("test.txt", span.source());
        Assert.assertEquals(0, span.startOffset());
        Assert.assertEquals(1, span.startLine());
        Assert.assertEquals(1, span.startColumn());
        Assert.assertEquals(5, span.endOffset());
        Assert.assertEquals(1, span.endLine());
        Assert.assertEquals(6, span.endColumn());
        Assert.assertEquals("hello", cursor.source().substring(span.startOffset(), span.endOffset()));
    }

    @Test
    public void testLF() {
        CharCursor cursor = new CharCursor("a\nb\nc");
        Assert.assertEquals('a', cursor.advance()); // at 1:2
        Assert.assertEquals(1, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(2, cursor.column());

        Assert.assertEquals('\n', cursor.advance()); // at 2:1
        Assert.assertEquals(2, cursor.offset());
        Assert.assertEquals(2, cursor.line());
        Assert.assertEquals(1, cursor.column());

        Assert.assertEquals('b', cursor.advance()); // at 2:2
        Assert.assertEquals(3, cursor.offset());
        Assert.assertEquals(2, cursor.line());
        Assert.assertEquals(2, cursor.column());

        Assert.assertEquals('\n', cursor.advance()); // at 3:1
        Assert.assertEquals(4, cursor.offset());
        Assert.assertEquals(3, cursor.line());
        Assert.assertEquals(1, cursor.column());

        Assert.assertEquals('c', cursor.advance()); // at 3:2
        Assert.assertEquals(5, cursor.offset());
        Assert.assertEquals(3, cursor.line());
        Assert.assertEquals(2, cursor.column());
    }

    @Test
    public void testCR() {
        // Standalone CR
        CharCursor cursor = new CharCursor("a\rb");
        Assert.assertEquals('a', cursor.advance());
        Assert.assertEquals('\r', cursor.advance());
        Assert.assertEquals(2, cursor.offset());
        Assert.assertEquals(2, cursor.line());
        Assert.assertEquals(1, cursor.column());

        Assert.assertEquals('b', cursor.advance());
        Assert.assertEquals(3, cursor.offset());
        Assert.assertEquals(2, cursor.line());
        Assert.assertEquals(2, cursor.column());
    }

    @Test
    public void testCRLF() {
        CharCursor cursor = new CharCursor("a\r\nb");
        Assert.assertEquals('a', cursor.advance());
        Assert.assertEquals(1, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(2, cursor.column());

        // Advance \r
        Assert.assertEquals('\r', cursor.advance());
        Assert.assertEquals(2, cursor.offset());
        Assert.assertEquals(1, cursor.line()); // Line is not incremented yet on \r of CRLF
        Assert.assertEquals(3, cursor.column());

        // Advance \n
        Assert.assertEquals('\n', cursor.advance());
        Assert.assertEquals(3, cursor.offset());
        Assert.assertEquals(2, cursor.line());
        Assert.assertEquals(1, cursor.column());

        // Advance 'b'
        Assert.assertEquals('b', cursor.advance());
        Assert.assertEquals(4, cursor.offset());
        Assert.assertEquals(2, cursor.line());
        Assert.assertEquals(2, cursor.column());
    }

    @Test
    public void testUnicodeBMPAndSurrogatePair() {
        // "你好" is BMP (2 chars)
        CharCursor cursor = new CharCursor("你好");
        CharCursor.Mark start = cursor.mark();
        Assert.assertEquals('你', cursor.advance());
        Assert.assertEquals(1, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(2, cursor.column());

        Assert.assertEquals('好', cursor.advance());
        Assert.assertEquals(2, cursor.offset());
        Assert.assertEquals(1, cursor.line());
        Assert.assertEquals(3, cursor.column());

        SourceSpan span = cursor.spanFrom(start);
        Assert.assertEquals("你好", cursor.source().substring(span.startOffset(), span.endOffset()));

        // Emoji / Surrogate pair: "\uD83D\uDE00" (grinning face, 2 UTF-16 code units)
        String emoji = "\uD83D\uDE00";
        CharCursor emojiCursor = new CharCursor(emoji);
        CharCursor.Mark emojiStart = emojiCursor.mark();
        Assert.assertEquals('\uD83D', emojiCursor.advance());
        Assert.assertEquals(1, emojiCursor.offset());
        Assert.assertEquals('\uDE00', emojiCursor.advance());
        Assert.assertEquals(2, emojiCursor.offset());
        SourceSpan emojiSpan = emojiCursor.spanFrom(emojiStart);
        Assert.assertEquals(0, emojiSpan.startOffset());
        Assert.assertEquals(2, emojiSpan.endOffset());
        Assert.assertEquals(emoji, emojiCursor.source().substring(emojiSpan.startOffset(), emojiSpan.endOffset()));
    }
}
