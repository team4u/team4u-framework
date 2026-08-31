package com.team4u.framework.flow.durable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 稳定检查点边界的分类描述，仅用于诊断与 DurableObserver 属性。 */
final class CheckpointReasons {

    static final class Reason {
        private final String kind;
        private final String path;
        private final Map<String, String> attributes;

        Reason(String kind, String path, Map<String, String> attributes) {
            this.kind = kind;
            this.path = path;
            this.attributes = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(attributes));
        }

        String kind() {
            return kind;
        }

        String path() {
            return path;
        }

        Map<String, String> attributes() {
            return attributes;
        }
    }

    private CheckpointReasons() {
    }

    static Reason invoke(String path) {
        return new Reason("INVOKE", path, Collections.<String, String>emptyMap());
    }

    static Reason complete(String path) {
        return new Reason("COMPLETE", path, Collections.<String, String>emptyMap());
    }

    static Reason boundary(String kind, String path) {
        return new Reason(kind, path, Collections.<String, String>emptyMap());
    }

    static Reason await(String point) {
        return new Reason("AWAIT", "$", Collections.singletonMap("resumePoint", point));
    }

    static Reason control(String path) {
        return new Reason("CONTROL", path, Collections.<String, String>emptyMap());
    }

    static Reason parallelBranch(String path, String branch) {
        return new Reason("PARALLEL_BRANCH", path,
                Collections.singletonMap("branch", branch));
    }

    static Reason parallelJoin(String path) {
        return new Reason("PARALLEL_JOIN", path, Collections.<String, String>emptyMap());
    }

    static Reason initial() {
        return new Reason("INITIAL", "$", Collections.<String, String>emptyMap());
    }

    static Reason resumeSignal(String point) {
        return new Reason("RESUME_SIGNAL", "$",
                Collections.singletonMap("resumePoint", point));
    }

    static Reason cancelled() {
        return new Reason("CANCELLED", "$", Collections.<String, String>emptyMap());
    }
}
