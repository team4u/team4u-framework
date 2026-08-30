package com.team4u.framework.fsm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 {@link StateMachine} 渲染为 Mermaid {@code stateDiagram-v2} 文本。
 * <p>
 * 渲染规则：
 * <ul>
 *     <li>每个状态通过别名声明（{@code state "名称" as sN}），状态名中的常见保留字符
 *         （回车、换行、双引号、分号、井号）会被转义为 Mermaid 安全形式；</li>
 *     <li>初始状态用 {@code [*] --> 初始状态} 表示；</li>
 *     <li>任意来源迁移渲染为合成节点 {@code *(any state)} 的出边，
 *         任意事件在边标签中显示为 {@code *(any event)}；</li>
 *     <li>保持原状态的迁移渲染为自环；</li>
 *     <li>守卫描述以 {@code [描述]} 追加在边标签之后。</li>
 * </ul>
 * 输出按状态首次出现顺序与迁移声明顺序确定，同一状态机的渲染结果稳定。
 *
 * @author jay.wu
 */
public final class StateMachineMermaid {

    private static final String ANY_STATE_ID = "any_state";
    private static final String ANY_STATE_LABEL = "*(any state)";
    private static final String ANY_EVENT_LABEL = "*(any event)";

    private StateMachineMermaid() {
    }

    /**
     * 渲染状态机为 Mermaid 状态图文本。
     *
     * @param machine 状态机，非空
     * @param <S>     状态类型
     * @param <E>     事件类型
     * @param <C>     业务上下文类型
     * @return Mermaid {@code stateDiagram-v2} 文本
     */
    public static <S, E, C> String render(StateMachine<S, E, C> machine) {
        if (machine == null) {
            throw new IllegalArgumentException("State machine cannot be null");
        }

        Map<S, String> stateIds = new LinkedHashMap<>();
        int sequence = 0;
        for (S state : machine.getStates()) {
            stateIds.put(state, "s" + sequence++);
        }

        boolean hasAnySource = false;
        for (Transition<S, E, C> transition : machine.getTransitions()) {
            if (transition.isAnySource()) {
                hasAnySource = true;
                break;
            }
        }

        StringBuilder diagram = new StringBuilder("stateDiagram-v2\n");
        diagram.append("    [*] --> ").append(stateIds.get(machine.getInitialState())).append('\n');
        for (Map.Entry<S, String> entry : stateIds.entrySet()) {
            diagram.append("    state \"").append(escape(entry.getKey().toString()))
                    .append("\" as ").append(entry.getValue()).append('\n');
        }
        if (hasAnySource) {
            diagram.append("    state \"").append(ANY_STATE_LABEL).append("\" as ")
                    .append(ANY_STATE_ID).append('\n');
        }

        for (Transition<S, E, C> transition : machine.getTransitions()) {
            String from = transition.isAnySource() ? ANY_STATE_ID : stateIds.get(transition.getFrom());
            String to = transition.isStay() ? from : stateIds.get(transition.getTo());
            diagram.append("    ").append(from).append(" --> ").append(to)
                    .append(" : ").append(edgeLabel(transition)).append('\n');
        }

        return diagram.toString();
    }

    private static String edgeLabel(Transition<?, ?, ?> transition) {
        StringBuilder label = new StringBuilder();
        if (transition.isAnyEvent()) {
            label.append(ANY_EVENT_LABEL);
        } else {
            label.append(escape(transition.getEvent().toString()));
        }
        if (transition.isGuarded()) {
            label.append(" [").append(escape(transition.getGuardDescription())).append(']');
        }
        return label.toString();
    }

    /**
     * 逐字符编码 Mermaid 文本中的特殊字符：回车丢弃、换行折叠为空格，
     * 双引号、分号与井号转为 {@code #...;} 形式的实体（{@code #quot;}、{@code #59;}、{@code #35;}）。
     * <p>
     * 分号会被 Mermaid 解析为语句分隔符、井号是实体引导符，二者必须转义；
     * 逐字符单遍编码保证产物中的实体不会被二次编码（例如引号编码出的 {@code #}
     * 不会再变成 {@code #35;}）。
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\r') {
                continue;
            }
            if (character == '\n') {
                escaped.append(' ');
            } else if (character == '"') {
                escaped.append("#quot;");
            } else if (character == ';') {
                escaped.append("#59;");
            } else if (character == '#') {
                escaped.append("#35;");
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
