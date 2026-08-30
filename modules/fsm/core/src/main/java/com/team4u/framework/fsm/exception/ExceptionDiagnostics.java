package com.team4u.framework.fsm.exception;

/**
 * 异常消息使用的安全诊断转换：把任意对象渲染为稳定的诊断文本。
 * <p>
 * 用户状态、事件或目标状态的 {@code toString()} 自身可能抛出异常。若在构造异常消息时
 * 直接做字符串拼接，{@code toString()} 的异常会替换掉原本要交付给调用方的守卫/动作异常。
 * 本工具捕获 {@code toString()} 的运行时异常并回退为类名占位符，保证原异常语义不被破坏，
 * 正常路径下的输出与直接拼接完全一致；JVM 级 {@code Error}（如栈溢出、内存耗尽）
 * 则照常向上传播，不被诊断路径吞掉——与状态机引擎仅包装 {@code Exception} 的语义一致。
 * 仅供本包内的异常构造使用。
 *
 * @author jay.wu
 */
final class ExceptionDiagnostics {

    private static final String TO_STRING_FAILED_PREFIX = "<toString failed: ";

    private ExceptionDiagnostics() {
    }

    /**
     * 把对象安全渲染为诊断文本。
     *
     * @param value 待渲染对象，允许为 {@code null}
     * @return {@code null} 或 {@code toString()} 返回 {@code null} 时返回 {@code "null"}；
     *         {@code toString()} 正常时原样返回其结果；抛出运行时异常时返回类名占位符；
     *         抛出 JVM 级 {@code Error} 时不捕获，照常向上传播
     */
    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            String text = String.valueOf(value);
            return text == null ? "null" : text;
        } catch (RuntimeException failure) {
            // 仅兜住 toString() 的运行时异常，回退为类名占位符；
            // JVM 级 Error（栈溢出、内存耗尽等）不在诊断路径吞掉，照常向上传播
            return TO_STRING_FAILED_PREFIX + value.getClass().getName() + '>';
        }
    }
}
