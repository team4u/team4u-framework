package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.Calendar;
import java.util.Date;

/**
 * DateUtil 单元测试
 *
 * @author jay.wu
 */
public class DateUtilTest {

    @Test
    public void testNow() {
        String now = DateUtil.now();
        Assert.assertNotNull(now);
        Assert.assertTrue(now.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void testFormat() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2023, Calendar.JANUARY, 1, 12, 0, 0);
        Date date = calendar.getTime();

        Assert.assertEquals("2023-01-01 12:00:00", DateUtil.format(date, "yyyy-MM-dd HH:mm:ss"));
        Assert.assertEquals("2023/01/01", DateUtil.format(date, "yyyy/MM/dd"));
        Assert.assertNull(DateUtil.format(null, "yyyy-MM-dd"));
    }

    @Test
    public void testParseWithPattern() {
        String dateStr = "2023-01-01 12:00:00";
        String pattern = "yyyy-MM-dd HH:mm:ss";
        Date date = DateUtil.parse(dateStr, pattern);

        Assert.assertNotNull(date);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        Assert.assertEquals(2023, calendar.get(Calendar.YEAR));
        Assert.assertEquals(Calendar.JANUARY, calendar.get(Calendar.MONTH));
        Assert.assertEquals(1, calendar.get(Calendar.DAY_OF_MONTH));
        Assert.assertEquals(12, calendar.get(Calendar.HOUR_OF_DAY));

        Assert.assertNull(DateUtil.parse(null, pattern));
    }

    @Test
    public void testAutoParse() {
        // 测试 yyyy-MM-dd HH:mm:ss
        Date date1 = DateUtil.parse("2023-01-01 12:00:00");
        Assert.assertNotNull(date1);
        Calendar c1 = Calendar.getInstance();
        c1.setTime(date1);
        Assert.assertEquals(12, c1.get(Calendar.HOUR_OF_DAY));

        // 测试 yyyy-MM-dd
        Date date2 = DateUtil.parse("2023-01-01");
        Assert.assertNotNull(date2);
        Calendar c2 = Calendar.getInstance();
        c2.setTime(date2);
        Assert.assertEquals(0, c2.get(Calendar.HOUR_OF_DAY));

        // 测试无效输入
        Assert.assertNull(DateUtil.parse(null));
        Assert.assertNull(DateUtil.parse(""));
        Assert.assertNull(DateUtil.parse("invalid-date"));
    }

    @Test
    public void testOffsetDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2023, Calendar.JANUARY, 1);
        Date date = calendar.getTime();

        // 向后偏移
        Date future = DateUtil.offsetDay(date, 1);
        Calendar cFuture = Calendar.getInstance();
        cFuture.setTime(future);
        Assert.assertEquals(2, cFuture.get(Calendar.DAY_OF_MONTH));

        // 向前偏移
        Date past = DateUtil.offsetDay(date, -1);
        Calendar cPast = Calendar.getInstance();
        cPast.setTime(past);
        Assert.assertEquals(2022, cPast.get(Calendar.YEAR));
        Assert.assertEquals(Calendar.DECEMBER, cPast.get(Calendar.MONTH));
        Assert.assertEquals(31, cPast.get(Calendar.DAY_OF_MONTH));

        Assert.assertNull(DateUtil.offsetDay(null, 1));
    }

    @Test
    public void testTimer() throws InterruptedException {
        DateUtil.TimeInterval timer = DateUtil.timer();
        // 时间标尺最小化：不变式「interval ≥ 实际休眠时长」与时长无关，50ms 足以验证
        Thread.sleep(50);
        long interval = timer.interval();
        Assert.assertTrue(interval >= 50);
    }
}
