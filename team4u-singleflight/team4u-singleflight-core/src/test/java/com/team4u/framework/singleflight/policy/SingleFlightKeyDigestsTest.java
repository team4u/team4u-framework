package com.team4u.framework.singleflight.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class SingleFlightKeyDigestsTest {

    @Test
    public void builtInSha256IsResolvable() {
        SingleFlightKeyDigest digest = SingleFlightKeyDigests.global().resolve("sha256");

        assertNotNull(digest);
        assertEquals(new Sha256KeyDigest().digest("13800138000"), digest.digest("13800138000"));
    }

    @Test
    public void unregisteredNameFails() {
        try {
            SingleFlightKeyDigests.global().resolve("nope");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("Singleflight key digest not registered: nope", expected.getMessage());
        }
    }

    @Test
    public void registerSupportsChainingAndOverride() {
        SingleFlightKeyDigest custom = new SingleFlightKeyDigest() {
            @Override
            public String key() {
                return "test-custom";
            }

            @Override
            public String digest(String renderedKey) {
                return "custom(" + renderedKey + ")";
            }
        };
        SingleFlightKeyDigest overridden = new SingleFlightKeyDigest() {
            @Override
            public String key() {
                return "test-custom";
            }

            @Override
            public String digest(String renderedKey) {
                return "overridden(" + renderedKey + ")";
            }
        };

        // 链式注册 + 同名后注册者覆盖先注册者（与 NamedKvStoreRegistry 行为一致）
        SingleFlightKeyDigests.global().register(custom).register(overridden);

        assertEquals("overridden(v)", SingleFlightKeyDigests.global().resolve("test-custom")
                .digest("v"));
    }

    @Test
    public void sha256DigestIsStable() {
        Sha256KeyDigest digest = new Sha256KeyDigest();

        assertEquals(digest.digest("a"), digest.digest("a"));
        assertEquals(64, digest.digest("a").length());
    }
}
