package com.team4u.framework.fsm.exception;

/**
 * 状态机定义不合法时抛出的异常。
 * <p>
 * 由构建器在定义期抛出（包括 DSL 配置阶段的即时报错与 {@code build()} 时
 * 的集中校验），涵盖规则不完整、重复标识、兜底规则之后存在不可达规则等
 * 定义期错误。
 *
 * @author jay.wu
 */
public final class StateMachineDefinitionException extends StateMachineException {

    private static final long serialVersionUID = 1L;

    public StateMachineDefinitionException(String message) {
        super(message);
    }
}
