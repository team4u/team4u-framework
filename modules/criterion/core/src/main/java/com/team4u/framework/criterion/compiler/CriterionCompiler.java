package com.team4u.framework.criterion.compiler;

import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 规则编译器接口 (SPI)
 * <p>
 * 负责将静态的 AST 节点编译为高性能的 {@link MatchPredicate}。
 *
 * @param <C> 支持的规则类型
 */
public interface CriterionCompiler<C extends Criterion> extends KeyedPolicy<Class<? extends Criterion>> {

    /**
     * 编译规则
     *
     * @param criterion 规则对象
     * @param visitor   访问者 (用于回调编译子节点)
     * @return 匹配断言函数
     */
    MatchPredicate compile(C criterion, CriterionVisitor<MatchPredicate> visitor);
}
