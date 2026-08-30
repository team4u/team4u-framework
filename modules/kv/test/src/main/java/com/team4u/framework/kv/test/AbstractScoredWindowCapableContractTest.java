package com.team4u.framework.kv.test;

import com.team4u.framework.kv.ScoredWindowCapable;
import com.team4u.framework.kv.ScoredWindowCapable.Offer;
import com.team4u.framework.kv.ScoredWindowCapable.Verdict;
import com.team4u.framework.kv.SpaceKey;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 计分窗口行为契约测试基类
 * <p>
 * 在 {@link AbstractCounterTtlContractTest} 基础上补充
 * {@link ScoredWindowCapable} 的行为契约：按 score 裁剪（== cutoff 被裁剪）、
 * maxCount 内条件添加、超限整体拒绝、members 为空的窥探语义、
 * 键 TTL 过期后整键消失重来、oldestScore 为最老成员。
 * 按 {@code instanceof ScoredWindowCapable} 存在性执行。
 * </p>
 *
 * @author jay.wu
 */
public abstract class AbstractScoredWindowCapableContractTest
        extends AbstractCounterTtlContractTest {

    private static Offer offer(long cutoff, long score, int maxCount,
                               long ttlMillis, String... members) {
        return Offer.builder()
                .cutoffScore(cutoff)
                .memberScore(score)
                .members(members == null || members.length == 0
                        ? Collections.<String>emptyList()
                        : java.util.Arrays.asList(members))
                .maxCount(maxCount)
                .ttlMillis(ttlMillis)
                .build();
    }

    @Test
    public void contractWindowPrunesMembersAtOrBelowCutoff() {
        if (!(store instanceof ScoredWindowCapable)) {
            return;
        }
        ScoredWindowCapable window = (ScoredWindowCapable) store;
        SpaceKey key = SpaceKey.of("contract", "window-prune");

        // score == cutoff 的成员被裁剪
        Verdict v = window.offer(key, offer(100, 100, 10, 0, "a"));
        assertTrue(v.isAccepted());
        assertEquals(1, v.getCount());
        v = window.offer(key, offer(100, 200, 10, 0, "b"));
        assertTrue(v.isAccepted());
        assertEquals("member with score == cutoff must be pruned", 1, v.getCount());
        assertEquals(200L, v.getOldestScore().longValue());

        // score > cutoff 的成员存活
        v = window.offer(key, offer(101, 300, 10, 0, "c"));
        assertTrue(v.isAccepted());
        assertEquals("member with score > cutoff must survive", 2, v.getCount());
        assertEquals(200L, v.getOldestScore().longValue());
    }

    @Test
    public void contractWindowAddsWhenWithinMaxCount() {
        if (!(store instanceof ScoredWindowCapable)) {
            return;
        }
        ScoredWindowCapable window = (ScoredWindowCapable) store;
        SpaceKey key = SpaceKey.of("contract", "window-add");

        Verdict v = window.offer(key, offer(0, 100, 3, 0, "a", "b"));
        assertTrue(v.isAccepted());
        assertEquals(2, v.getCount());
        assertEquals(100L, v.getOldestScore().longValue());

        // 裁剪掉旧成员（score == cutoff）腾出空间后，可继续添加
        v = window.offer(key, offer(100, 300, 2, 0, "c", "d"));
        assertTrue(v.isAccepted());
        assertEquals(2, v.getCount());
        assertEquals(300L, v.getOldestScore().longValue());
    }

    @Test
    public void contractWindowRejectsWhenOverLimitWithoutAdding() {
        if (!(store instanceof ScoredWindowCapable)) {
            return;
        }
        ScoredWindowCapable window = (ScoredWindowCapable) store;
        SpaceKey key = SpaceKey.of("contract", "window-reject");

        assertTrue(window.offer(key, offer(0, 100, 2, 0, "a")).isAccepted());

        // 超限：不添加任何成员
        Verdict rejected = window.offer(key, offer(0, 200, 2, 0, "b", "c"));
        assertFalse(rejected.isAccepted());
        assertEquals(1, rejected.getCount());
        assertEquals(100L, rejected.getOldestScore().longValue());

        // 拒绝不产生副作用：窥探确认计数不变
        Verdict peek = window.offer(key, offer(0, 0, 2, 0));
        assertTrue(peek.isAccepted());
        assertEquals("rejection must not add any member", 1, peek.getCount());
    }

    @Test
    public void contractWindowPeekNeverRejectsAndDoesNotAdd() {
        if (!(store instanceof ScoredWindowCapable)) {
            return;
        }
        ScoredWindowCapable window = (ScoredWindowCapable) store;
        SpaceKey key = SpaceKey.of("contract", "window-peek");

        // 空窗口窥探：accepted=true、count=0、oldestScore=null
        Verdict empty = window.offer(key, offer(0, 0, 1, 0));
        assertTrue(empty.isAccepted());
        assertEquals(0, empty.getCount());
        assertNull(empty.getOldestScore());

        assertTrue(window.offer(key, offer(0, 100, 1, 0, "a")).isAccepted());

        // 已满窗口窥探（maxCount=1）也永不拒绝，且不添加成员
        Verdict peek = window.offer(key, offer(0, 0, 1, 0));
        assertTrue("peek must never be rejected", peek.isAccepted());
        assertEquals(1, peek.getCount());
        assertEquals(100L, peek.getOldestScore().longValue());

        Verdict again = window.offer(key, offer(0, 0, 1, 0));
        assertEquals("peek must not add member", 1, again.getCount());
    }

    @Test
    public void contractWindowExpiresAsWholeKeyAfterTtl() {
        if (!(store instanceof ScoredWindowCapable)) {
            return;
        }
        ScoredWindowCapable window = (ScoredWindowCapable) store;
        SpaceKey key = SpaceKey.of("contract", "window-ttl");

        assertTrue(window.offer(key, offer(0, 100, 1, 1000, "a")).isAccepted());
        advanceMillis(999);
        // 未过期：仍拒绝超限添加
        assertFalse(window.offer(key, offer(0, 200, 1, 1000, "b")).isAccepted());

        advanceMillis(1);
        // 键过期：整键消失，即使 cutoff 不裁剪任何旧 score 也从零重来
        Verdict v = window.offer(key, offer(0, 50, 1, 1000, "c"));
        assertTrue(v.isAccepted());
        assertEquals("expired window must restart from zero", 1, v.getCount());
        assertEquals(50L, v.getOldestScore().longValue());
    }

    @Test
    public void contractWindowTtlRefreshedByEachSuccessfulOffer() {
        if (!(store instanceof ScoredWindowCapable)) {
            return;
        }
        ScoredWindowCapable window = (ScoredWindowCapable) store;
        SpaceKey key = SpaceKey.of("contract", "window-ttl-refresh");

        assertTrue(window.offer(key, offer(0, 100, 10, 1000, "a")).isAccepted());
        advanceMillis(600);
        // 窥探（成功操作）刷新 TTL
        assertTrue(window.offer(key, offer(0, 0, 10, 1000)).isAccepted());
        advanceMillis(600);
        // 若窥探未刷新 TTL，此处键已过期、计数归零；刷新后旧成员仍存活
        Verdict v = window.offer(key, offer(0, 200, 10, 1000, "b"));
        assertTrue(v.isAccepted());
        assertEquals("successful offers must refresh the window TTL", 2, v.getCount());
        assertEquals(100L, v.getOldestScore().longValue());
    }

    @Test
    public void contractWindowOldestScoreIsMinimumAfterPrune() {
        if (!(store instanceof ScoredWindowCapable)) {
            return;
        }
        ScoredWindowCapable window = (ScoredWindowCapable) store;
        SpaceKey key = SpaceKey.of("contract", "window-oldest");

        // 乱序添加多个成员
        assertTrue(window.offer(key, offer(0, 300, 10, 0, "a")).isAccepted());
        assertTrue(window.offer(key, offer(0, 100, 10, 0, "b")).isAccepted());
        assertTrue(window.offer(key, offer(0, 200, 10, 0, "c")).isAccepted());

        Verdict v = window.offer(key, offer(150, 0, 10, 0));
        assertTrue(v.isAccepted());
        assertEquals(2, v.getCount());
        assertEquals("oldestScore must be the minimum surviving score",
                200L, v.getOldestScore().longValue());

        // 全部裁剪后窗口为空：oldestScore = null
        Verdict empty = window.offer(key, offer(400, 0, 10, 0));
        assertTrue(empty.isAccepted());
        assertEquals(0, empty.getCount());
        assertNull(empty.getOldestScore());
    }
}
