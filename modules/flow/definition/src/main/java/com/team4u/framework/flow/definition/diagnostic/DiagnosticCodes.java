package com.team4u.framework.flow.definition.diagnostic;

/**
 * 诊断错误码常量集合（Diagnostic Codes）。
 *
 * @author jay.wu
 */
public final class DiagnosticCodes {

    private DiagnosticCodes() { }

    // Parser 阶段错误码
    public static final String DSL_SYNTAX_ERROR = "DSL_SYNTAX_ERROR";
    public static final String DSL_UNEXPECTED_TOKEN = "DSL_UNEXPECTED_TOKEN";
    public static final String DSL_UNSUPPORTED_SCHEMA = "DSL_UNSUPPORTED_SCHEMA";
    public static final String DUPLICATE_STEP_PROJECT = "DUPLICATE_STEP_PROJECT";
    public static final String DUPLICATE_STEP_MERGE = "DUPLICATE_STEP_MERGE";
    public static final String DUPLICATE_OTHERWISE = "DUPLICATE_OTHERWISE";
    public static final String DUPLICATE_JOIN = "DUPLICATE_JOIN";
    public static final String DUPLICATE_CONFIG_KEY = "DUPLICATE_CONFIG_KEY";

    // Definition & Structural 阶段错误码
    public static final String INVALID_DEFINITION = "INVALID_DEFINITION";
    public static final String AMBIGUOUS_TARGET_FLOW = "AMBIGUOUS_TARGET_FLOW";
    public static final String INVALID_FLOW_ID = "INVALID_FLOW_ID";
    public static final String INVALID_FLOW_VERSION = "INVALID_FLOW_VERSION";
    public static final String INVALID_CONTROL = "INVALID_CONTROL";
    public static final String EMPTY_PARALLEL = "EMPTY_PARALLEL";
    public static final String EMPTY_FIRST_APPLICABLE = "EMPTY_FIRST_APPLICABLE";

    // Symbol 阶段错误码
    public static final String UNKNOWN_OPERATION = "UNKNOWN_OPERATION";
    public static final String UNKNOWN_FLOW = "UNKNOWN_FLOW";
    public static final String UNKNOWN_POLICY = "UNKNOWN_POLICY";
    public static final String UNKNOWN_PROJECTOR = "UNKNOWN_PROJECTOR";
    public static final String UNKNOWN_MERGER = "UNKNOWN_MERGER";
    public static final String UNKNOWN_KEY_PROJECTION = "UNKNOWN_KEY_PROJECTION";
    public static final String UNKNOWN_JOIN = "UNKNOWN_JOIN";
    public static final String UNKNOWN_RESUME_POINT = "UNKNOWN_RESUME_POINT";
    public static final String DUPLICATE_FLOW_ID = "DUPLICATE_FLOW_ID";
    public static final String CYCLIC_FLOW_CALL = "CYCLIC_FLOW_CALL";

    // Type 阶段错误码
    public static final String TYPE_MISMATCH = "TYPE_MISMATCH";
    public static final String INVALID_ROUTE_CASE = "INVALID_ROUTE_CASE";
    public static final String INVALID_PROJECTOR = "INVALID_PROJECTOR";
    public static final String INVALID_MERGER = "INVALID_MERGER";
    public static final String INVALID_RECOVER_INPUT = "INVALID_RECOVER_INPUT";
    public static final String INVALID_OPTIONAL_STEP = "INVALID_OPTIONAL_STEP";
    public static final String NO_TYPE_CODEC = "NO_TYPE_CODEC";
    public static final String UNSUPPORTED_SPEC = "UNSUPPORTED_SPEC";

    // Compiler 阶段错误码
    public static final String DUPLICATE_SCOPE = "DUPLICATE_SCOPE";
    public static final String DUPLICATE_BRANCH = "DUPLICATE_BRANCH";
    public static final String DUPLICATE_RESUME_POINT = "DUPLICATE_RESUME_POINT";
    public static final String PARALLEL_AWAIT = "PARALLEL_AWAIT";
    public static final String PARALLEL_PERSISTENT_POLICY = "PARALLEL_PERSISTENT_POLICY";
    public static final String MISSING_BINDING = "MISSING_BINDING";
    public static final String BINDING_TYPE = "BINDING_TYPE";
    public static final String ARRAY_ROUTE_KEY = "ARRAY_ROUTE_KEY";
    public static final String DUPLICATE_ROUTE_CASE = "DUPLICATE_ROUTE_CASE";
}
