package com.team4u.framework.mask;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves externally governed field masking rules.
 * <p>
 * This contract is intentionally independent of configuration and serialization frameworks.
 * Adapter modules install their own resolver implementation.
 */
public interface MaskRuleResolver {

    /**
     * Neutral resolver used when no dynamic-rule adapter is installed.
     */
    MaskRuleResolver NO_OP = (className, fieldName) -> null;

    /**
     * Finds the mask policy key configured for a field.
     *
     * @param className fully qualified class name
     * @param fieldName field name
     * @return configured policy key, or null when no rule matches
     */
    String findRule(String className, String fieldName);

    /**
     * Global resolver holder with a lifecycle for adapter installation.
     */
    final class Global {
        private static final AtomicReference<MaskRuleResolver> RESOLVER =
                new AtomicReference<>(NO_OP);

        private Global() {
        }

        public static MaskRuleResolver get() {
            return RESOLVER.get();
        }

        public static void install(MaskRuleResolver newResolver) {
            if (newResolver == null) {
                throw new IllegalArgumentException("MaskRuleResolver must not be null");
            }
            RESOLVER.set(newResolver);
        }

        /**
         * Removes the global resolver only when it is still the expected owner.
         *
         * @param expectedResolver resolver supplied by the installing owner
         * @return true when the expected resolver was installed and removed
         */
        public static boolean uninstall(MaskRuleResolver expectedResolver) {
            if (expectedResolver == null) {
                return false;
            }
            return RESOLVER.compareAndSet(expectedResolver, NO_OP);
        }

        /**
         * Unconditionally resets the global resolver. Reserved for tests and explicit administration.
         */
        public static void reset() {
            RESOLVER.set(NO_OP);
        }
    }
}
