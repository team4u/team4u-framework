package com.team4u.framework.translator.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 原始响应输入对象 (Domain Model)
 * <p>
 * 代表来自内部异常或外部第三方的原始数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RawResponse {

    /**
     * 来源领域/类型
     */
    private String domain;

    /**
     * 原始码
     */
    private String code;

    /**
     * 原始信息
     */
    private String message;

    /**
     * 可选：原始异常堆栈
     */
    private Throwable cause;

    /**
     * 静态工厂方法，用于快速创建对象（无异常）
     *
     * @param domain  来源领域/类型
     * @param code    原始码
     * @param message 原始信息
     * @return RawResponse
     */
    public static RawResponse of(String domain, String code, String message) {
        return new RawResponse(domain, code, message, null);
    }

    /**
     * 静态工厂方法，用于快速创建对象（携带异常）
     *
     * @param domain 来源领域/类型
     * @param cause  抛出的初始异常
     * @return RawResponse
     */
    public static RawResponse of(String domain, Throwable cause) {
        return new RawResponse(domain, cause.getClass().getSimpleName(), cause.getMessage(), cause);
    }
}
