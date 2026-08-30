package com.team4u.framework.singleflight.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team4u.framework.serializer.json.JsonUtil;

/**
 * 会话信封：存储在 {@code singleflight.session} space 中的不可变执行状态载体。
 * <p>
 * 状态机为 {@code PENDING → 终态}：执行者获得锁后写入 PENDING，加载完成经 CAS 发布
 * 成功（可缓存 / 不可缓存）或失败终态。等待者读会话拿结果或失败，执行者崩溃后
 * 会话与锁分离，等待者可凭“PENDING 且锁消失”接管重试。
 * </p>
 * <p>
 * {@code token} 刻意放在第一个字段：终态发布以 PENDING 信封的完整 JSON 作为 CAS 期望值，
 * 只要接管者已写入新 token 的 PENDING，旧执行者（token 已失效）的晚到写入就无法覆盖
 * 接管者的会话——这是 token fencing 的实现边界。
 * </p>
 *
 * @author jay.wu
 */
public final class SessionEnvelope {

    /**
     * 执行中：已获得锁、加载函数尚未完成
     */
    public static final String STATE_PENDING = "PENDING";
    /**
     * 成功且可缓存终态：执行者将另行写入结果缓存
     */
    public static final String STATE_SUCCESS_CACHEABLE = "SUCCESS_CACHEABLE";
    /**
     * 成功但不可缓存终态：等待者可读取结果，但不写结果缓存
     */
    public static final String STATE_SUCCESS_NOT_CACHEABLE = "SUCCESS_NOT_CACHEABLE";
    /**
     * 失败终态：等待者收到重构的执行失败异常
     */
    public static final String STATE_FAILURE = "FAILURE";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectNode json;

    private SessionEnvelope(ObjectNode json) {
        this.json = json;
    }

    /**
     * 构造 PENDING 会话：携带锁 token 与开始时间。
     */
    public static SessionEnvelope pending(String token, long nowMillis) {
        ObjectNode node = NODES.objectNode();
        node.put("token", token);
        node.put("state", STATE_PENDING);
        node.put("startedAtMillis", nowMillis);
        return new SessionEnvelope(node);
    }

    /**
     * 构造成功终态：按 cacheWhen 结果区分可缓存与否，并携带结果 JSON。
     */
    public static SessionEnvelope success(String token, JsonNode result, boolean cacheable,
                                          long finishedAtMillis) {
        ObjectNode node = NODES.objectNode();
        node.put("token", token);
        node.put("state", cacheable ? STATE_SUCCESS_CACHEABLE : STATE_SUCCESS_NOT_CACHEABLE);
        node.set("result", result);
        node.put("finishedAtMillis", finishedAtMillis);
        return new SessionEnvelope(node);
    }

    /**
     * 构造失败终态：仅保留错误消息文本，不跨线程传递原异常对象。
     */
    public static SessionEnvelope failure(String token, String errorMessage, long finishedAtMillis) {
        ObjectNode node = NODES.objectNode();
        node.put("token", token);
        node.put("state", STATE_FAILURE);
        node.put("error", errorMessage == null ? "loader failed" : errorMessage);
        node.put("finishedAtMillis", finishedAtMillis);
        return new SessionEnvelope(node);
    }

    /**
     * 从存储中的 JSON 反序列化信封；非 JSON 对象视为数据损坏，立即失败。
     */
    public static SessionEnvelope of(String json) {
        Object parsed = JsonUtil.parseObj(json);
        if (!(parsed instanceof ObjectNode)) {
            throw new IllegalArgumentException("Session envelope must be a JSON object");
        }
        return new SessionEnvelope((ObjectNode) parsed);
    }

    static SessionEnvelope fromObjectNode(ObjectNode json) {
        return new SessionEnvelope(json);
    }

    /**
     * 返回深拷贝的可变 JSON 视图，供外部按需加工而不破坏本信封的不可变性。
     */
    ObjectNode mutableJson() {
        return json.deepCopy();
    }

    /**
     * 锁持有者令牌，即 CAS 的 fencing 边界。
     */
    public String token() {
        JsonNode token = json.get("token");
        return token == null || token.isNull() ? null : token.asText();
    }

    public String state() {
        JsonNode state = json.get("state");
        return state == null ? null : state.asText();
    }

    public boolean hasState(String state) {
        return state != null && state.equals(state());
    }

    /**
     * 是否为终态（任一成功或失败）：终态会话可直接复用，无需再等锁。
     */
    public boolean isTerminal() {
        return STATE_SUCCESS_CACHEABLE.equals(state())
                || STATE_SUCCESS_NOT_CACHEABLE.equals(state())
                || STATE_FAILURE.equals(state());
    }

    public JsonNode result() {
        return json.get("result");
    }

    public String errorMessage() {
        JsonNode error = json.get("error");
        return error == null || error.isNull() ? null : error.asText();
    }

    /**
     * 序列化为 JSON 文本；PENDING 的输出即终态 CAS 的期望值。
     */
    public String toJson() {
        return json.toString();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SessionEnvelope && json.equals(((SessionEnvelope) obj).json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }
}
