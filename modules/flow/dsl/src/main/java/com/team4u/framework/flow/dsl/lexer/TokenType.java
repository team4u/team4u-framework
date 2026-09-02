package com.team4u.framework.flow.dsl.lexer;

/**
 * DSL 词法记号类型枚举（Token Type）。
 *
 * @author jay.wu
 */
public enum TokenType {
    // 关键字
    SCHEMA("schema"),
    FLOW("flow"),
    VERSION("version"),
    STEP("step"),
    CALL("call"),
    PROJECT("project"),
    MERGE("merge"),
    OPTIONAL("optional"),
    NAMED("named"),
    POLICY("policy"),
    KEY("key"),
    RETRY("retry"),
    TIMEOUT("timeout"),
    ROUTE("route"),
    CASE("case"),
    OTHERWISE("otherwise"),
    FIRST_APPLICABLE("firstApplicable"),
    RECOVER("recover"),
    BODY("body"),
    ON_FAILURE("onFailure"),
    PARALLEL("parallel"),
    BRANCH("branch"),
    JOIN("join"),
    AWAIT("await"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    SKIPPED("skipped"),
    FAILED("failed"),
    SCOPE("scope"),

    // 字面量与标识符
    IDENTIFIER(null),
    STRING(null),
    NUMBER(null),
    DURATION(null),

    // 标点与界符
    LBRACE("{"),
    RBRACE("}"),
    COLON(":"),
    COMMA(","),
    EQUALS("="),

    // 文件结尾
    EOF("<EOF>");

    private final String keyword;

    TokenType(String keyword) {
        this.keyword = keyword;
    }

    public String keyword() {
        return keyword;
    }
}
