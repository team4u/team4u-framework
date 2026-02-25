package com.team4u.framework.policy;

/**
 * 策略异常
 * <p>
 * 用于策略注册、校验等操作时的错误报告。
 *
 * @author jay
 */
public class PolicyException extends RuntimeException {

    /**
     * 期望的策略类型
     */
    private final Class<?> expectedPolicyClass;

    /**
     * 实际的策略类型
     */
    private final Class<?> actualPolicyClass;

    /**
     * 策略标识键
     */
    private final Object policyKey;

    /**
     * 不支持的注册表类型
     */
    private final Class<?> unsupportedRegistryClass;

    private PolicyException(Builder builder) {
        super(builder.message, builder.cause);
        this.expectedPolicyClass = builder.expectedPolicyClass;
        this.actualPolicyClass = builder.actualPolicyClass;
        this.policyKey = builder.policyKey;
        this.unsupportedRegistryClass = builder.unsupportedRegistryClass;
    }

    /**
     * 创建策略类型不匹配的异常
     *
     * @param expectedPolicyClass 期望的策略类型
     * @param actualPolicyClass   实际的策略类型
     * @return 策略异常
     */
    public static PolicyException typeMismatch(Class<?> expectedPolicyClass, Class<?> actualPolicyClass) {
        return builder()
                .message("Policy type mismatch, expected: " + expectedPolicyClass.getName() + ", got: "
                        + actualPolicyClass.getName())
                .expectedPolicyClass(expectedPolicyClass)
                .actualPolicyClass(actualPolicyClass)
                .build();
    }

    /**
     * 创建不支持的注册表类型的异常
     *
     * @param expectedRegistryClass 期望的注册表类型
     * @param actualRegistryClass   实际的注册表类型
     * @return 策略异常
     */
    public static PolicyException unsupportedRegistry(Class<?> expectedRegistryClass, Class<?> actualRegistryClass) {
        return builder()
                .message("Only " + expectedRegistryClass.getSimpleName() + " is supported, got: "
                        + actualRegistryClass.getName())
                .unsupportedRegistryClass(actualRegistryClass)
                .build();
    }

    /**
     * 创建策略为空的异常
     *
     * @return 策略异常
     */
    public static PolicyException policyNull() {
        return builder()
                .message("Policy cannot be null")
                .build();
    }

    /**
     * 创建策略标识键为空的异常
     *
     * @param policyClass 策略类型
     * @return 策略异常
     */
    public static PolicyException policyKeyNull(Class<?> policyClass) {
        return builder()
                .message("Policy key cannot be null for policy type: " + policyClass.getName())
                .expectedPolicyClass(policyClass)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Class<?> getExpectedPolicyClass() {
        return expectedPolicyClass;
    }

    public Class<?> getActualPolicyClass() {
        return actualPolicyClass;
    }

    public Object getPolicyKey() {
        return policyKey;
    }

    public Class<?> getUnsupportedRegistryClass() {
        return unsupportedRegistryClass;
    }

    public static class Builder {
        private String message;
        private Throwable cause;
        private Class<?> expectedPolicyClass;
        private Class<?> actualPolicyClass;
        private Object policyKey;
        private Class<?> unsupportedRegistryClass;

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder expectedPolicyClass(Class<?> expectedPolicyClass) {
            this.expectedPolicyClass = expectedPolicyClass;
            return this;
        }

        public Builder actualPolicyClass(Class<?> actualPolicyClass) {
            this.actualPolicyClass = actualPolicyClass;
            return this;
        }

        public Builder policyKey(Object policyKey) {
            this.policyKey = policyKey;
            return this;
        }

        public Builder unsupportedRegistryClass(Class<?> unsupportedRegistryClass) {
            this.unsupportedRegistryClass = unsupportedRegistryClass;
            return this;
        }

        public PolicyException build() {
            return new PolicyException(this);
        }
    }
}
