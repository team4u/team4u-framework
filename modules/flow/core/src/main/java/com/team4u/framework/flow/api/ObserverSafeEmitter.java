package com.team4u.framework.flow.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 观察者安全派发器（Observer Safe Emitter）。
 *
 * <p>统一收敛框架内对 {@link FlowObserver#onEvent} 的异常隔离策略：
 * <ul>
 *   <li><b>观察者异常不影响执行</b>：回调抛出的 {@link RuntimeException} 一律捕获并记录日志，
 *   绝不向执行引擎传播，不会改变流程编排与执行结果；</li>
 *   <li><b>Error 不拦截</b>：回调抛出的 {@link Error}（如 StackOverflowError、OutOfMemoryError）
 *   视为不可恢复的 JVM 级故障，原样向上传播，不做任何吞并；</li>
 *   <li><b>日志限流</b>：同一观察者实例的首次异常以 warn 级别记录完整信息，
 *   后续重复异常降级为 debug 级别，按观察者实例限流，防止高频事件导致日志刷屏。</li>
 * </ul>
 * </p>
 *
 * <p>线程安全：内部异常计数基于 synchronized 的身份键 Map，多线程并发派发安全；
 * 观察者实例本身仍需自行保证线程安全（参见 {@link FlowObserver} 的线程模型契约）。</p>
 *
 * @author jay.wu
 */
public final class ObserverSafeEmitter {
    private static final Logger log = LoggerFactory.getLogger(ObserverSafeEmitter.class);

    /** 同一观察者实例首个异常之后，仅以 debug 记录的最大次数。 */
    private static final int MAX_DEBUG_PER_OBSERVER = 5;

    private ObserverSafeEmitter() { }

    /** 观察者实例到已记录异常次数的映射（身份键，防止观察者被静态表强引用泄漏）。 */
    private static final Map<FlowObserver, Integer> FAILURES =
            Collections.synchronizedMap(new IdentityHashMap<FlowObserver, Integer>());

    /**
     * 安全派发单个事件给指定观察者。
     *
     * @param observer 流程事件观察者，不能为 null
     * @param event    流程事件对象，不能为 null
     */
    public static void emit(FlowObserver observer, FlowObserver.Event event) {
        try {
            observer.onEvent(event);
        } catch (RuntimeException error) {
            record(observer, event, error);
        }
    }

    /**
     * 记录观察者异常：首次 warn，后续按实例限流降级为 debug。
     */
    private static void record(FlowObserver observer, FlowObserver.Event event,
                               RuntimeException error) {
        int count;
        synchronized (FAILURES) {
            Integer previous = FAILURES.get(observer);
            count = previous == null ? 1 : previous.intValue() + 1;
            FAILURES.put(observer, Integer.valueOf(count));
        }
        if (count == 1) {
            log.warn("FlowObserver|onEvent|failure|observer={}|type={}|path={}",
                    observer.getClass().getName(), event.type(),
                    event.descriptor().path(), error);
        } else if (count <= MAX_DEBUG_PER_OBSERVER + 1) {
            log.debug("FlowObserver|onEvent|repeated-failure|observer={}|type={}|count={}",
                    observer.getClass().getName(), event.type(), Integer.valueOf(count), error);
        }
    }
}
