package com.team4u.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Flow 编排或投影过程中聚合的诊断异常。所有 Problem 在构造时拼接为单条消息，
 * 通过 {@link #problems()} 可获取结构化列表。
 */
public final class FlowBuildException extends IllegalArgumentException {
    private final List<Problem> problems;

    FlowBuildException(List<Problem> problems) {
        super(buildMessage(problems));
        this.problems = Collections.unmodifiableList(new ArrayList<Problem>(problems));
    }

    private static String buildMessage(List<Problem> problems) {
        if (problems == null || problems.isEmpty()) {
            return "Flow build failed";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            Problem problem = problems.get(i);
            sb.append(problem.code()).append(" at ").append(problem.path()).append(": ").append(problem.message());
        }
        return sb.toString();
    }

    public List<Problem> problems() {
        return problems;
    }

    /** 单条诊断信息。 */
    public static final class Problem {
        private final String code;
        private final String path;
        private final String message;

        public Problem(String code, String path, String message) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.path = Objects.requireNonNull(path, "path must not be null");
            this.message = Objects.requireNonNull(message, "message must not be null");
        }

        public String code() {
            return code;
        }

        public String path() {
            return path;
        }

        public String message() {
            return message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Problem problem = (Problem) o;
            return code.equals(problem.code) && path.equals(problem.path) && message.equals(problem.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, path, message);
        }

        @Override
        public String toString() {
            return "Problem[code=" + code + ", path=" + path + ", message=" + message + "]";
        }
    }
}
