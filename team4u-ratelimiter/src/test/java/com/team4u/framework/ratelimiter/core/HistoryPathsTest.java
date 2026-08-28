package com.team4u.framework.ratelimiter.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 历史路径导航工具单元测试：Map/Bean 导航、List 下标、缺失路径、元素类型转换
 *
 * @author jay.wu
 */
public class HistoryPathsTest {

    @Test
    public void mapNavigation() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("history", Arrays.asList(1L, 2L));
        Map<String, Object> context = Collections.singletonMap("client", inner);

        assertEquals(Arrays.asList(1L, 2L),
                HistoryPaths.extractTimestamps(context, "client.history"));
    }

    @Test
    public void listIndexNavigation() {
        Map<String, Object> context = Collections.singletonMap("rows",
                Arrays.asList(
                        Collections.singletonMap("ts", 100L),
                        Collections.singletonMap("ts", 200L)));

        assertEquals(Long.valueOf(200L), HistoryPaths.navigate(context, "rows.1.ts"));
        assertNull("下标越界返回 null", HistoryPaths.navigate(context, "rows.9.ts"));
        assertNull("非数字下标返回 null", HistoryPaths.navigate(context, "rows.x.ts"));
    }

    @Test
    public void beanGetterNavigation() {
        Client client = new Client(Arrays.asList(5L));
        assertEquals(Arrays.asList(5L), HistoryPaths.extractTimestamps(client, "history"));
        assertEquals("O1", HistoryPaths.navigate(new Order(), "id"));
        assertNull("无 getter 的属性返回 null", HistoryPaths.navigate(new Order(), "missing"));
    }

    @Test
    public void beanBooleanStyleGetter() {
        // is 前缀 getter 也可导航
        assertEquals(Boolean.TRUE, HistoryPaths.navigate(new Flag(), "enabled"));
    }

    @Test
    public void missingOrNullPathYieldsEmpty() {
        assertEquals(Collections.emptyList(),
                HistoryPaths.extractTimestamps(new HashMap<String, Object>(), "a.b.c"));
        assertEquals(Collections.emptyList(), HistoryPaths.extractTimestamps(null, "a.b"));
        assertEquals(Collections.emptyList(), HistoryPaths.extractTimestamps(new HashMap<>(), null));
    }

    @Test
    public void nonListEndYieldsEmpty() {
        Map<String, Object> context = Collections.singletonMap("name", "jay");
        assertEquals(Collections.emptyList(), HistoryPaths.extractTimestamps(context, "name"));
    }

    @Test
    public void elementConversionNumberAndDateOnly() {
        List<Object> mixed = Arrays.asList(1L, 2, 3.7, new Date(5000), "x", null);
        Map<String, Object> context = Collections.singletonMap("history", mixed);

        assertEquals("仅 Number/Date 转换，其余跳过",
                Arrays.asList(1L, 2L, 3L, 5000L),
                HistoryPaths.extractTimestamps(context, "history"));
    }

    public static class Client {

        private final List<Long> history;

        Client(List<Long> history) {
            this.history = history;
        }

        public List<Long> getHistory() {
            return history;
        }
    }

    public static class Order {

        public String getId() {
            return "O1";
        }
    }

    public static class Flag {

        public boolean isEnabled() {
            return true;
        }
    }
}
