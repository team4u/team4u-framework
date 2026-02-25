package com.team4u.framework.criterion;

import lombok.Getter;
import lombok.Setter;
import com.team4u.framework.criterion.trace.TraceRecorder;

import java.util.HashMap;
import java.util.Map;

/**
 * 匹配上下文
 * <p>
 * 🚨 <b>线程安全警告：</b>
 * 本类是非线程安全的。它包含可变的属性 Map 和追踪记录器。
 * 在并发执行场景下，必须确保为每个独立的请求或线程创建新的 {@code MatchContext} 实例。
 * 严禁在多个线程间共享同一个上下文对象。
 */
@Getter
public class MatchContext {
    private final Object actual;
    /**
     * 上下文属性 Map
     * <p>
     * 注意：此 Map 在通过 {@link #withActual(Object)} 创建的新上下文中是共享的。
     */
    private final Map<String, Object> attributes;

    /**
     * 追踪记录器 (可选，为 null 时表示不追踪)
     */
    @Setter
    private TraceRecorder recorder;

    /**
     * 是否启用严格模式
     * <p>
     * 默认为 false。
     * - false: 发生异常时记录错误日志并返回 false
     * - true: 发生异常时抛出 CriterionEvaluationException
     */
    @Setter
    private boolean strictMode;

    /**
     * 属性加载解析器 (可选)
     * 用于在属性 Map 中找不到对应 key 时动态获取值
     */
    @Setter
    private AttributeResolver attributeResolver;

    public MatchContext(Object actual) {
        this(actual, new HashMap<>());
    }

    /**
     * 内部全参构造，支持共享 attributes
     */
    protected MatchContext(Object actual, Map<String, Object> attributes) {
        this.actual = actual;
        this.attributes = attributes;
    }

    public static MatchContext of(Object actual) {
        return new MatchContext(actual);
    }

    /**
     * 创建上下文（带初始属性）
     */
    public static MatchContext of(Object actual, Map<String, Object> attributes) {
        return of(actual).setAttributes(attributes);
    }

    /**
     * 创建一个新的上下文，共享当前上下文的属性 Map，但替换实际值
     * 同时传递追踪器，保证子节点能写入同一个树
     * <p>
     * <b>警告：</b>由于属性 Map 是共享的，在多线程环境中使用此方法创建的对象时，
     * 仍需注意由于属性写操作导致的线程冲突。
     *
     * @param newActual 新的实际值
     * @return 新的 MatchContext
     */
    public MatchContext withActual(Object newActual) {
        MatchContext newContext = new MatchContext(newActual, this.attributes);
        newContext.setRecorder(this.recorder);
        newContext.setStrictMode(this.strictMode);
        newContext.setAttributeResolver(this.attributeResolver);
        return newContext;
    }

    /**
     * 设置严格模式并返回当前对象
     *
     * @param strictMode 是否启用严格模式
     * @return 当前对象
     */
    public MatchContext withStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getActual() {
        return (T) actual;
    }

    public MatchContext setAttribute(String key, Object value) {
        if (value == null) {
            return this;
        }

        attributes.put(key, value);
        return this;
    }

    /**
     * 批量设置属性
     */
    public MatchContext setAttributes(Map<String, Object> attributes) {
        if (attributes != null) {
            this.attributes.putAll(attributes);
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        Object value = attributes.get(key);
        if (value == null && !attributes.containsKey(key) && attributeResolver != null) {
            value = attributeResolver.resolve(this, key);
            attributes.put(key, value);
        }
        return (T) value;
    }

    /**
     * 获取属性值，如果不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @param <T>          值类型
     * @return 属性值或默认值
     */
    public <T> T getAttribute(String key, T defaultValue) {
        T value = getAttribute(key);
        return value != null ? value : defaultValue;
    }
}