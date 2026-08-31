package com.team4u.framework.flow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ParallelResults 类型签名守护：firstAccepted()/homogeneousCollect() 收紧为通配符返回类型后，
 * 不再对异构分支集合做虚假的具体类型承诺；真正的类型安全路径仍是 outcome(branch) 与 Values.get(branch)。
 * 直接构造 ParallelResults（包级构造器）以隔离验证合并语义，无需驱动真实 Parallel 执行。
 */
public class ParallelResultsTest {

    @Test
    public void factoryPreservesDeclarationOrderAndDefensivelyCopiesInputs() {
        Branch<String, String> first = Branch.of("same",
                (context, input) -> Outcome.accepted(input));
        Branch<String, String> second = Branch.of("same",
                (context, input) -> Outcome.accepted(input));
        Outcome<String> firstOutcome = Outcome.accepted("first");
        Outcome<String> secondOutcome = Outcome.accepted("second");
        List<Branch<?, ?>> branches = new ArrayList<Branch<?, ?>>(
                Arrays.<Branch<?, ?>>asList(first, second));
        List<Outcome<?>> outcomes = new ArrayList<Outcome<?>>(
                Arrays.<Outcome<?>>asList(firstOutcome, secondOutcome));

        ParallelResults results = ParallelResults.of(branches, outcomes);
        branches.clear();
        outcomes.clear();

        assertEquals(Arrays.<Branch<?, ?>>asList(first, second), results.branches());
        assertSame(firstOutcome, results.outcome(first));
        assertSame(secondOutcome, results.outcome(second));
        assertThrowsException(UnsupportedOperationException.class,
                () -> results.branches().clear());
    }

    @Test
    public void factoryValidatesListsEntriesSizesAndDuplicateTokenIdentity() {
        Branch<String, String> first = Branch.of("first",
                (context, input) -> Outcome.accepted(input));
        Branch<String, String> sameNameButDistinct = Branch.of("first",
                (context, input) -> Outcome.accepted(input));
        Outcome<String> accepted = Outcome.accepted("value");

        assertThrowsException(NullPointerException.class,
                () -> ParallelResults.of(null, Collections.<Outcome<?>>emptyList()));
        assertThrowsException(NullPointerException.class,
                () -> ParallelResults.of(Collections.<Branch<?, ?>>emptyList(), null));
        assertThrowsException(IllegalArgumentException.class,
                () -> ParallelResults.of(Collections.<Branch<?, ?>>singletonList(first),
                        Collections.<Outcome<?>>emptyList()));
        assertThrowsException(NullPointerException.class,
                () -> ParallelResults.of(Collections.<Branch<?, ?>>singletonList(null),
                        Collections.<Outcome<?>>singletonList(accepted)));
        assertThrowsException(NullPointerException.class,
                () -> ParallelResults.of(Collections.<Branch<?, ?>>singletonList(first),
                        Collections.<Outcome<?>>singletonList(null)));
        assertThrowsException(IllegalArgumentException.class,
                () -> ParallelResults.of(Arrays.<Branch<?, ?>>asList(first, first),
                        Arrays.<Outcome<?>>asList(accepted, accepted)));

        ParallelResults distinctTokens = ParallelResults.of(
                Arrays.<Branch<?, ?>>asList(first, sameNameButDistinct),
                Arrays.<Outcome<?>>asList(accepted, accepted));
        assertEquals(Arrays.<Branch<?, ?>>asList(first, sameNameButDistinct), distinctTokens.branches());
    }

    @Test
    public void packagePrivateConstructorUsesTheSameValidation() {
        Branch<String, String> branch = Branch.of("branch",
                (context, input) -> Outcome.accepted(input));
        Outcome<String> accepted = Outcome.accepted("value");

        assertThrowsException(IllegalArgumentException.class,
                () -> new ParallelResults(Arrays.<Branch<?, ?>>asList(branch, branch),
                        Arrays.<Outcome<?>>asList(accepted, accepted)));
    }

    @Test
    public void firstAcceptedReturnsOpaqueOutcomeInDeclarationOrder() {
        Branch<String, String> first = Branch.of("first",
                (context, input) -> Outcome.accepted(input));
        Branch<String, String> second = Branch.of("second",
                (context, input) -> Outcome.accepted(input));
        ParallelResults results = new ParallelResults(
                Arrays.asList(first, second),
                Arrays.asList(Outcome.accepted("first"), Outcome.accepted("second")));

        Outcome<?> outcome = results.firstAccepted();
        assertTrue(outcome instanceof Outcome.Accepted<?>);
        assertEquals("first", ((Outcome.Accepted<?>) outcome).value());

        // 无 Accepted：返回 Skipped，丢弃 branch 原始 Rejected 细节
        ParallelResults none = new ParallelResults(
                Arrays.asList(first, second),
                Arrays.asList(Outcome.rejected(Reason.of("NO", "no")),
                        Outcome.rejected(Reason.of("ALSO_NO", "also no"))));
        Outcome<?> skipped = none.firstAccepted();
        assertTrue(skipped instanceof Outcome.Skipped<?>);
        assertEquals("NO_APPLICABLE_BRANCH",
                ((Outcome.Skipped<?>) skipped).reason().code());
    }

    @Test
    public void homogeneousCollectReturnsOpaqueElementList() {
        Branch<String, String> first = Branch.of("first",
                (context, input) -> Outcome.accepted(input));
        Branch<String, String> second = Branch.of("second",
                (context, input) -> Outcome.accepted(input));
        ParallelResults results = new ParallelResults(
                Arrays.asList(first, second),
                Arrays.asList(Outcome.accepted("first"), Outcome.accepted("second")));

        Outcome<List<?>> collected = results.homogeneousCollect();
        assertTrue(collected instanceof Outcome.Accepted<?>);
        assertEquals(Arrays.asList("first", "second"),
                ((Outcome.Accepted<List<?>>) collected).value());

        // 首个非 Accepted 原样返回，保留 Failed 细节
        Failure failure = Failure.of("BOOM", "boom");
        ParallelResults partial = new ParallelResults(
                Arrays.asList(first, second),
                Arrays.asList(Outcome.accepted("first"), Outcome.failed(failure)));
        Outcome<List<?>> propagated = partial.homogeneousCollect();
        assertTrue(propagated instanceof Outcome.Failed<?>);
        assertSame(failure, ((Outcome.Failed<?>) propagated).failure());
    }

    @Test
    public void outcomeBranchAndValuesGetRemainTypeSafe() {
        Branch<String, Integer> length = Branch.of("length",
                (context, input) -> Outcome.accepted(input.length()));
        Branch<String, String> upper = Branch.of("upper",
                (context, input) -> Outcome.accepted(input.toUpperCase()));
        ParallelResults results = new ParallelResults(
                Arrays.asList(length, upper),
                Arrays.asList(Outcome.accepted(4), Outcome.accepted("FLOW")));

        // outcome(branch)：按 token 精确产出 Outcome<Integer> / Outcome<String>
        Outcome<Integer> lengthOutcome = results.outcome(length);
        assertEquals(Integer.valueOf(4), ((Outcome.Accepted<Integer>) lengthOutcome).value());
        Outcome<String> upperOutcome = results.outcome(upper);
        assertEquals("FLOW", ((Outcome.Accepted<String>) upperOutcome).value());

        // allAccepted() + Values.get(branch)：按 token 精确产出 Integer / String
        Outcome<ParallelResults.Values> all = results.allAccepted();
        assertTrue(all instanceof Outcome.Accepted<?>);
        ParallelResults.Values values = ((Outcome.Accepted<ParallelResults.Values>) all).value();
        assertEquals(Integer.valueOf(4), values.get(length));
        assertEquals("FLOW", values.get(upper));
    }

    private static <T extends Throwable> void assertThrowsException(
            Class<T> expectedType, Runnable executable) {
        try {
            executable.run();
            fail("Expected " + expectedType.getName() + " was not thrown");
        } catch (Throwable actual) {
            if (!expectedType.isInstance(actual)) {
                fail("Expected " + expectedType.getName() + " but was " + actual.getClass().getName());
            }
        }
    }
}
