package com.team4u.framework.parser;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

public class TokenCursorTest {

    @Test
    public void testEmpty() {
        TokenCursor<String> cursor = new TokenCursor<>(Collections.emptyList());
        Assert.assertFalse(cursor.hasNext());
        Assert.assertNull(cursor.peek());
        Assert.assertNull(cursor.peek(0));
        Assert.assertNull(cursor.peek(1));
        Assert.assertNull(cursor.previous());
        Assert.assertEquals(0, cursor.position());

        TokenCursor<String> nullCursor = new TokenCursor<>(null);
        Assert.assertFalse(nullCursor.hasNext());
    }

    @Test(expected = NoSuchElementException.class)
    public void testAdvanceOnEmptyThrows() {
        TokenCursor<String> cursor = new TokenCursor<>(Collections.emptyList());
        cursor.advance();
    }

    @Test
    public void testPeekAndLookahead() {
        List<String> tokens = Arrays.asList("T0", "T1", "T2");
        TokenCursor<String> cursor = new TokenCursor<>(tokens);

        Assert.assertTrue(cursor.hasNext());
        Assert.assertEquals("T0", cursor.peek());
        Assert.assertEquals("T0", cursor.peek(0));
        Assert.assertEquals("T1", cursor.peek(1));
        Assert.assertEquals("T2", cursor.peek(2));
        Assert.assertNull(cursor.peek(3));
        Assert.assertNull(cursor.peek(-1));
        Assert.assertEquals(0, cursor.position());
    }

    @Test
    public void testPreviousAndAdvance() {
        List<String> tokens = Arrays.asList("A", "B", "C");
        TokenCursor<String> cursor = new TokenCursor<>(tokens);

        Assert.assertNull(cursor.previous());
        Assert.assertEquals("A", cursor.advance());
        Assert.assertEquals("A", cursor.previous());
        Assert.assertEquals(1, cursor.position());

        Assert.assertEquals("B", cursor.advance());
        Assert.assertEquals("B", cursor.previous());
        Assert.assertEquals(2, cursor.position());

        Assert.assertEquals("C", cursor.advance());
        Assert.assertEquals("C", cursor.previous());
        Assert.assertEquals(3, cursor.position());
        Assert.assertFalse(cursor.hasNext());
    }

    @Test
    public void testMarkAndReset() {
        List<String> tokens = Arrays.asList("A", "B", "C", "D");
        TokenCursor<String> cursor = new TokenCursor<>(tokens);

        cursor.advance(); // position 1 ("B" next)
        int mark1 = cursor.mark();
        Assert.assertEquals(1, mark1);

        cursor.advance(); // position 2 ("C" next)
        cursor.advance(); // position 3 ("D" next)
        Assert.assertEquals("D", cursor.peek());

        // Reset to mark1
        cursor.reset(mark1);
        Assert.assertEquals(1, cursor.position());
        Assert.assertEquals("B", cursor.peek());
    }

    @Test
    public void testMultipleReset() {
        List<String> tokens = Arrays.asList("1", "2", "3", "4", "5");
        TokenCursor<String> cursor = new TokenCursor<>(tokens);

        int mark0 = cursor.mark();
        cursor.advance();
        int mark1 = cursor.mark();
        cursor.advance();
        cursor.advance();
        int mark3 = cursor.mark();

        cursor.reset(mark1);
        Assert.assertEquals(1, cursor.position());
        Assert.assertEquals("2", cursor.peek());

        cursor.reset(mark3);
        Assert.assertEquals(3, cursor.position());
        Assert.assertEquals("4", cursor.peek());

        cursor.reset(mark0);
        Assert.assertEquals(0, cursor.position());
        Assert.assertEquals("1", cursor.peek());
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testResetNegativeThrows() {
        TokenCursor<String> cursor = new TokenCursor<>(Arrays.asList("A", "B"));
        cursor.reset(-1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testResetBeyondSizeThrows() {
        TokenCursor<String> cursor = new TokenCursor<>(Arrays.asList("A", "B"));
        cursor.reset(3);
    }
}
