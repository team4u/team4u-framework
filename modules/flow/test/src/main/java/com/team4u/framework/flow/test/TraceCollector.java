package com.team4u.framework.flow.test;

import com.team4u.framework.flow.FlowObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 线程安全的执行事件收集器：按到达顺序保留全部 {@link FlowObserver.Event}。
 *
 * <p>框架本身隔离 observer 异常（回调抛出的运行时异常不影响执行）；
 * 本 stub 自身不抛异常，只做收集。Parallel 分支事件来自多个工作线程，
 * 因此使用 CopyOnWriteArrayList 保证线程安全。</p>
 */
public final class TraceCollector implements FlowObserver {

    private final CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<Event>();

    @Override
    public void onEvent(Event event) {
        Objects.requireNonNull(event, "event must not be null");
        events.add(event);
    }

    /** 全部已收集事件（不可变快照）。 */
    public List<Event> events() {
        return Collections.unmodifiableList(new ArrayList<Event>(events));
    }

    public int eventCount() {
        return events.size();
    }

    /** 按类型过滤的事件列表（保持到达顺序）。 */
    public List<Event> ofType(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        List<Event> matched = new ArrayList<Event>();
        for (Event event : events) {
            if (event.type() == type) {
                matched.add(event);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    /** 全部事件的类型序列（便于顺序断言）。 */
    public List<Type> types() {
        List<Type> types = new ArrayList<Type>(events.size());
        for (Event event : events) {
            types.add(event.type());
        }
        return Collections.unmodifiableList(types);
    }

    /** 指定类型事件关联的节点 path 列表（结构事件的 descriptor 即节点描述符）。 */
    public List<String> nodePaths(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        List<String> paths = new ArrayList<String>();
        for (Event event : events) {
            if (event.type() == type) {
                paths.add(event.descriptor().path());
            }
        }
        return Collections.unmodifiableList(paths);
    }

    /** 清空已收集事件。 */
    public void clear() {
        events.clear();
    }
}
