package com.team4u.framework.flow.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.api.Operation;

/**
 * 流程构建、校验与静态编译期间聚合的诊断异常。
 *
 * <p>用于在流程编译或合法性检查（如重复 scope 名称、非法 await 节点、并行内挂起、缺失 Operation 绑定等）
 * 聚合所有诊断问题 {@link Problem} 并一次性报告，便于开发者快速定位所有结构设计缺陷。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class FlowBuildException extends IllegalArgumentException {
    /** 聚合的全部构建问题诊断列表。 */
    private final List<Problem> problems;

    /**
     * 构造流程构建异常。
     *
     * @param problems 问题诊断列表，不能为 null 或空
     */
    public FlowBuildException(List<Problem> problems) {
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

    /**
     * 单条构建诊断问题。
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode
    @ToString
    public static final class Problem {
        /** 诊断错误码（如 DUPLICATE_SCOPE, ILLEGAL_AWAIT, DUPLICATE_BRANCH 等）。 */
        private final String code;
        /** 发生问题的 AST 节点路径（如 {@code $.0.1}）。 */
        private final String path;
        /** 详细诊断说明信息。 */
        private final String message;

        /**
         * 构造单条问题。
         *
         * @param code    错误码，不能为 null
         * @param path    节点路径，不能为 null
         * @param message 错误信息，不能为 null
         */
        public Problem(String code, String path, String message) {
            this.code = Objects.requireNonNull(code, "code must not be null");
            this.path = Objects.requireNonNull(path, "path must not be null");
            this.message = Objects.requireNonNull(message, "message must not be null");
        }
    }
}

