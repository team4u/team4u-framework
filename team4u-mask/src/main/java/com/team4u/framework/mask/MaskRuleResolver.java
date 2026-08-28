package com.team4u.framework.mask;

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
        private static volatile MaskRuleResolver resolver = NO_OP;

        private Global() {
        }

        public static MaskRuleResolver get() {
            return resolver;
        }

        public static void install(MaskRuleResolver newResolver) {
            if (newResolver == null) {
                throw new IllegalArgumentException("MaskRuleResolver must not be null");
            }
            resolver = newResolver;
        }

        public static void reset() {
            resolver = NO_OP;
        }
    }
}
