package com.team4u.framework.singleflight.policy;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.model.VariableExtractor;
import lombok.EqualsAndHashCode;

import java.util.Set;

/**
 * Compiled criterion predicate. Compilation happens during rule load, so syntax
 * errors fail before a new rule replaces the old one.
 *
 * @author jay.wu
 */
@EqualsAndHashCode(of = "expression")
public final class SingleFlightCondition {

    private final String expression;

    private SingleFlightCondition(String expression) {
        this.expression = expression;
    }

    public static SingleFlightCondition compile(String expression) {
        Criteria.global().compileExpression(expression);
        return new SingleFlightCondition(expression);
    }

    public boolean matches(Object actual) {
        return Criteria.global().matches(expression, actual);
    }

    public boolean matches(MatchContext context) {
        return Criteria.global().matches(expression, context);
    }

    public Set<String> variableNames() {
        return VariableExtractor.extract(Criteria.global().parse(expression));
    }

    public String expression() {
        return expression;
    }
}
