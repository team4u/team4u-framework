package com.team4u.framework.flow;

import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.model.FlowDiagnosticCodes;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.ParallelResults;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class JoinsTest {

    @Test
    public void testFirstAcceptedAsWithPrimitiveType() {
        Branch<Void, Integer> branch1 = Branch.of("b1", (ctx, in) -> Outcome.accepted(42));
        Branch<Void, Integer> branch2 = Branch.of("b2", (ctx, in) -> Outcome.accepted(100));

        ParallelResults results = ParallelResults.of(
                Arrays.asList(branch1, branch2),
                Arrays.asList(Outcome.accepted(42), Outcome.accepted(100))
        );

        JoinStrategy<Integer> join = Joins.firstAcceptedAs(int.class);
        Outcome<Integer> outcome = join.join(results);
        Assert.assertEquals(Outcome.Kind.ACCEPTED, outcome.kind());
        Assert.assertEquals(Integer.valueOf(42), ((Outcome.Accepted<Integer>) outcome).value());
    }

    @Test
    public void testFirstAcceptedAsTypeMismatch() {
        Branch<Void, String> branch = Branch.of("b1", (ctx, in) -> Outcome.accepted("not_an_int"));
        ParallelResults results = ParallelResults.of(
                Arrays.asList(branch),
                Arrays.asList(Outcome.accepted("not_an_int"))
        );

        JoinStrategy<Integer> join = Joins.firstAcceptedAs(int.class);
        Outcome<Integer> outcome = join.join(results);
        Assert.assertEquals(Outcome.Kind.FAILED, outcome.kind());
        Assert.assertEquals(FlowDiagnosticCodes.TYPE_MISMATCH, ((Outcome.Failed<Integer>) outcome).failure().code());
    }

    @Test
    public void testCollectAsWithPrimitiveType() {
        Branch<Void, Integer> branch1 = Branch.of("b1", (ctx, in) -> Outcome.accepted(1));
        Branch<Void, Integer> branch2 = Branch.of("b2", (ctx, in) -> Outcome.accepted(2));

        ParallelResults results = ParallelResults.of(
                Arrays.asList(branch1, branch2),
                Arrays.asList(Outcome.accepted(1), Outcome.accepted(2))
        );

        JoinStrategy<List<Integer>> join = Joins.collectAs(int.class);
        Outcome<List<Integer>> outcome = join.join(results);
        Assert.assertEquals(Outcome.Kind.ACCEPTED, outcome.kind());
        Assert.assertEquals(Arrays.asList(1, 2), ((Outcome.Accepted<List<Integer>>) outcome).value());
    }

    @Test
    public void testCollectAsTypeMismatch() {
        Branch<Void, Object> branch1 = Branch.of("b1", (ctx, in) -> Outcome.accepted(1));
        Branch<Void, Object> branch2 = Branch.of("b2", (ctx, in) -> Outcome.accepted("mismatch"));

        ParallelResults results = ParallelResults.of(
                Arrays.asList(branch1, branch2),
                Arrays.asList(Outcome.accepted(1), Outcome.accepted("mismatch"))
        );

        JoinStrategy<List<Integer>> join = Joins.collectAs(int.class);
        Outcome<List<Integer>> outcome = join.join(results);
        Assert.assertEquals(Outcome.Kind.FAILED, outcome.kind());
        Assert.assertEquals(FlowDiagnosticCodes.TYPE_MISMATCH, ((Outcome.Failed<List<Integer>>) outcome).failure().code());
    }
}
