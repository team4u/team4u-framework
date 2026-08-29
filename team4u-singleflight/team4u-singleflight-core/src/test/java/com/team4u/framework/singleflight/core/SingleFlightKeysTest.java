package com.team4u.framework.singleflight.core;

import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.singleflight.policy.Sha256KeyDigest;
import com.team4u.framework.singleflight.policy.SingleFlightKeyDigest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SingleFlightKeysTest {

    @Test
    public void composeKeepsPointIsolationAndSafeSpaceKey() {
        String first = SingleFlightKeys.compose("point.one", "value", null);
        String second = SingleFlightKeys.compose("point.two", "value", null);

        assertEquals("point.one_value", first);
        assertEquals("point.two_value", second);
        assertNotEquals(first, second);
        SpaceKey.of(SingleFlightEngine.LOCK_SPACE, first);
    }

    @Test
    public void composeEncodesIllegalCharacters() {
        String key = SingleFlightKeys.compose("point", "a:b c/d", null);

        assertEquals("point_a%3ab%20c%2fd", key);
        SpaceKey.of(SingleFlightEngine.SESSION_SPACE, key);
    }

    @Test
    public void composeWithoutDigestKeepsLongKeysVerbatim() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            value.append("0123456789");
        }
        String key = SingleFlightKeys.compose("point", value.toString(), null);

        assertEquals("point_" + value, key);
    }

    @Test
    public void composeWithDigestReplacesBusinessKeyEntirely() {
        String key = SingleFlightKeys.compose("point", "13800138000", new Sha256KeyDigest());

        // point 保持明文便于排查；业务 key 全量摘要，不保留可读前缀
        assertTrue(key.startsWith("point_"));
        assertFalse(key.contains("13800138000"));
        assertEquals("point_" + new Sha256KeyDigest().digest("13800138000"), key);
        SpaceKey.of(SingleFlightEngine.CACHE_SPACE, key);
    }

    @Test
    public void digestIsStableAndCollisionResistant() {
        Sha256KeyDigest digest = new Sha256KeyDigest();
        assertEquals(digest.digest("same-value"), digest.digest("same-value"));
        assertNotEquals(SingleFlightKeys.compose("p", "AAAAAAAAAA", digest),
                SingleFlightKeys.compose("p", "AAAAAAAAAB", digest));
    }

    @Test
    public void customDigestPolicyIsAppliedAndEncoded() {
        // 自定义策略返回非安全字符也统一过百分号编码兜底
        SingleFlightKeyDigest colon = new SingleFlightKeyDigest() {
            @Override
            public String key() {
                return "colon";
            }

            @Override
            public String digest(String renderedKey) {
                return "x:" + renderedKey;
            }
        };
        String key = SingleFlightKeys.compose("point", "v", colon);

        assertEquals("point_x%3av", key);
        SpaceKey.of(SingleFlightEngine.LOCK_SPACE, key);
    }

    @Test
    public void separatorIsEscaped() {
        assertNotEquals(SingleFlightKeys.compose("a", "b_c", null),
                SingleFlightKeys.compose("a_b", "c", null));
        assertTrue(SingleFlightKeys.compose("a", "b_c", null).contains("%5f"));
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
    }

    @Test
    public void composeRejectsBlankDigestedKey() {
        SingleFlightKeyDigest blank = new SingleFlightKeyDigest() {
            @Override
            public String key() {
                return "blank";
            }

            @Override
            public String digest(String renderedKey) {
                return " ";
            }
        };
        try {
            SingleFlightKeys.compose("point", "value", blank);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("digested key is empty"));
        }
    }

    private void assertComposeFails(String point, String value) {
        try {
            SingleFlightKeys.compose(point, value, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Singleflight"));
        }
    }

    private void assertComposeEncodesPoint(String point) {
        String key = SingleFlightKeys.compose(point, "value", null);

        assertEquals("a%3ab_value", key);
        assertFalse(key.contains(":"));
        SpaceKey.of(SingleFlightEngine.LOCK_SPACE, key);
    }
}
