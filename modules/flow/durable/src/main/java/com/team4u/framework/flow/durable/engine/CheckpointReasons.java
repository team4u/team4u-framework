package com.team4u.framework.flow.durable.engine;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.team4u.framework.flow.durable.DurableObserver;
import com.team4u.framework.flow.model.Reason;

/**
 * 检查点提交原因描述工厂（Checkpoint Reasons Factory）。
 *
 * <p>用于在触发 CAS 持久化提交时，提供结构化的诊断原因与属性信息，供 {@link DurableObserver} 监听消费。</p>
 *
 * @author jay.wu
 */
public final class CheckpointReasons {

    /**
     * 检查点触发原因元数据描述对象。
     */
    @Getter
    @Accessors(fluent = true)
    public static final class Reason {
        /** 检查点种类（如 INITIAL / INVOKE / AWAIT / CONTROL / CANCELLED）。 */
        private final String kind;
        /** 节点拓扑路径。 */
        private final String path;
        /** 附加属性键值对。 */
        private final Map<String, String> attributes;

        public Reason(String kind, String path, Map<String, String> attributes) {
            this.kind = kind;
            this.path = path;
            this.attributes = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(attributes));
        }
    }

    private CheckpointReasons() {
    }

    public static Reason invoke(String path) {
        return new Reason("INVOKE", path, Collections.<String, String>emptyMap());
    }

    public static Reason complete(String path) {
        return new Reason("COMPLETE", path, Collections.<String, String>emptyMap());
    }

    public static Reason boundary(String kind, String path) {
        return new Reason(kind, path, Collections.<String, String>emptyMap());
    }

    public static Reason await(String point) {
        return new Reason("AWAIT", "$", Collections.singletonMap("resumePoint", point));
    }

    public static Reason control(String path) {
        return new Reason("CONTROL", path, Collections.<String, String>emptyMap());
    }

    public static Reason parallelBranch(String path, String branch) {
        return new Reason("PARALLEL_BRANCH", path,
                Collections.singletonMap("branch", branch));
    }

    public static Reason parallelJoin(String path) {
        return new Reason("PARALLEL_JOIN", path, Collections.<String, String>emptyMap());
    }

    public static Reason initial() {
        return new Reason("INITIAL", "$", Collections.<String, String>emptyMap());
    }

    public static Reason resumeSignal(String point) {
        return new Reason("RESUME_SIGNAL", "$",
                Collections.singletonMap("resumePoint", point));
    }

    public static Reason cancelled() {
        return new Reason("CANCELLED", "$", Collections.<String, String>emptyMap());
    }
}

