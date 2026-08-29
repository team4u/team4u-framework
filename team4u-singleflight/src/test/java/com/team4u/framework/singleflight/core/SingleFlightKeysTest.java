package com.team4u.framework.singleflight.core;

import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SingleFlightKeysTest {

    @Test
    public void composeKeepsPointIsolationAndSafeSpaceKey() {
        String first = SingleFlightKeys.compose("point.one", "value", 128);
        String second = SingleFlightKeys.compose("point.two", "value", 128);

        assertEquals("point.one_value", first);
        assertEquals("point.two_value", second);
        assertNotEquals(first, second);
        SpaceKey.of(SingleFlightEngine.LOCK_SPACE, first);
    }

    @Test
    public void composeEncodesIllegalCharacters() {
        String key = SingleFlightKeys.compose("point", "a:b c/d", 128);

        assertEquals("point_a%3ab%20c%2fd", key);
        SpaceKey.of(SingleFlightEngine.SESSION_SPACE, key);
    }

    @Test
    public void composeDigestsLongKeys() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            value.append("0123456789");
        }
        String key = SingleFlightKeys.compose("point", value.toString(), 128);

        assertTrue(key.length() < value.length());
        assertTrue(key.startsWith("point_"));
        assertTrue(key.contains("#sha256_"));
        assertFalse(key.contains(":0123456789"));
        SpaceKey.of(SingleFlightEngine.CACHE_SPACE, key);
    }

    @Test
    public void composeDigestIsStableAndCollisionResistantForLongValues() {
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            first.append("AAAAAAAAAA");
            second.append("AAAAAAAAAB");
        }
        assertNotEquals(SingleFlightKeys.compose("p", first.toString(), 128),
                SingleFlightKeys.compose("p", second.toString(), 128));
    }

    @Test
    public void separatorAndDigestMarkerAreEscaped() {
        assertNotEquals(SingleFlightKeys.compose("a", "b_c", 128),
                SingleFlightKeys.compose("a_b", "c", 128));
        assertTrue(SingleFlightKeys.compose("a", "b_c", 128).contains("%5f"));
        assertTrue(SingleFlightKeys.compose("a", "#sha256_x", 128).contains("%23"));
    }

    @Test
    public void composeRejectsInvalidInputs() {
        assertComposeFails(null, "value");
        assertComposeFails("", "value");
        assertComposeFails(" ", "value");
        assertComposeEncodesPoint("a:b");
        assertComposeFails("point", null);
        assertComposeFails("point", "");
        assertComposeFails("point", " ");
        assertComposeFails("point", "value", 0);
        assertComposeFails("point", "value", -1);
    }

    private void assertComposeFails(String point, String value) {
        assertComposeFails(point, value, 128);
    }

    private void assertComposeEncodesPoint(String point) {
        String key = SingleFlightKeys.compose(point, "value", 128);

        assertEquals("a%3ab_value", key);
        assertFalse(key.contains(":"));
        SpaceKey.of(SingleFlightEngine.LOCK_SPACE, key);
    }

    private void assertComposeFails(String point, String value, int threshold) {
        try {
            SingleFlightKeys.compose(point, value, threshold);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Singleflight"));
        }
    }
}
