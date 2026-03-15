package com.team4u.framework.config.db;

import com.team4u.framework.base.jdbc.JdbcUtil;
import com.team4u.framework.base.util.ThreadUtil;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.spi.ConfigSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 数据库配置源及监听器集成测试。
 */
public class DbConfigSourceTest {

    /**
     * H2 内存数据库数据源
     */
    private DataSource dataSource;

    @Before
    public void setUp() throws SQLException {
        // 初始化 H2 内存数据库，开启 MySQL 兼容模式
        String jdbcUrl = "jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(jdbcUrl);
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        // 建表 DDL
        String ddl = "DROP TABLE IF EXISTS system_config;\n" +
                "CREATE TABLE system_config\n" +
                "(\n" +
                "    id           BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,\n" +
                "    enabled      TINYINT      DEFAULT 1  NOT NULL,\n" +
                "    config_type  VARCHAR(32)  DEFAULT '' NOT NULL,\n" +
                "    config_key   VARCHAR(50)  DEFAULT '' NOT NULL,\n" +
                "    config_value VARCHAR(500) DEFAULT '' NOT NULL,\n" +
                "    description  VARCHAR(255) DEFAULT '' NOT NULL,\n" +
                "    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                "    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n" +
                "    PRIMARY KEY (id),\n" +
                "    UNIQUE INDEX uniq_config_id (config_type, config_key)\n" +
                ");";
        JdbcUtil.execute(dataSource, ddl);

        // 插入初始测试数据
        JdbcUtil.execute(dataSource, "insert into system_config(config_type, config_key, config_value) values (?, ?, ?)",
                "app", "name", "Team4uApp");
        JdbcUtil.execute(dataSource, "insert into system_config(config_type, config_key, config_value) values (?, ?, ?)",
                "app", "port", "8080");
        JdbcUtil.execute(dataSource, "insert into system_config(config_type, config_key, config_value) values (?, ?, ?)",
                "db", "url", "jdbc:mysql://localhost:3306/test");
    }

    @After
    public void tearDown() throws SQLException {
        JdbcUtil.execute(dataSource, "DROP TABLE IF EXISTS system_config");
    }

    /**
     * 全量加载场景。
     */
    @Test
    public void testLoadAll() {
        DbConfigSource source = new DbConfigSource("DB-All", 100, dataSource);
        Map<String, ConfigEntry> config = source.load();

        assertEquals(3, config.size());
        assertEquals("Team4uApp", config.get("app.name").getValue());
        assertEquals("8080", config.get("app.port").getValue());
        assertEquals("jdbc:mysql://localhost:3306/test", config.get("db.url").getValue());
    }


    /**
     * 软删除逻辑测试。
     */
    @Test
    public void testSoftDeleteTombstone() throws SQLException {
        DbConfigSource source = new DbConfigSource("DB-All", 100, dataSource);

        // 将 app.port 软删除
        JdbcUtil.execute(dataSource, "UPDATE system_config SET enabled = 0 WHERE config_key = 'port'");

        Map<String, ConfigEntry> config = source.load();

        ConfigEntry portEntry = config.get("app.port");
        assertNotNull("软删除条目仍应存在于结果集中", portEntry);
        assertEquals("软删除条目的值应为 TOMBSTONE_VALUE", ConfigSource.TOMBSTONE_VALUE, portEntry.getValue());
        assertTrue("isEmptyOrDeleted() 应返回 true", portEntry.isEmptyOrDeleted());

        // 未删除的条目应保持正常
        assertFalse("正常条目不应被判断为已删除", config.get("app.name").isEmptyOrDeleted());
    }

    /**
     * 自定义映射测试，验证表名和字段名可配置。
     */
    @Test
    public void testCustomConfiguration() throws SQLException {
        // 创建自定义表
        String customDdl = "CREATE TABLE my_custom_config\n" +
                "(\n" +
                "    my_type  VARCHAR(32),\n" +
                "    my_key   VARCHAR(50),\n" +
                "    my_value VARCHAR(500),\n" +
                "    my_status TINYINT DEFAULT 1,\n" +
                "    my_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n" +
                ");";
        JdbcUtil.execute(dataSource, customDdl);

        JdbcUtil.execute(dataSource, "insert into my_custom_config(my_type, my_key, my_value) values (?, ?, ?)",
                "custom", "foo", "bar");

        // 配置映射选项
        DbConfigOptions options = new DbConfigOptions()
                .setTableName("my_custom_config")
                .setConfigTypeColumn("my_type")
                .setConfigKeyColumn("my_key")
                .setConfigValueColumn("my_value")
                .setEnabledColumn("my_status")
                .setUpdateTimeColumn("my_time");

        DbConfigSource source = new DbConfigSource("Custom", 100, dataSource, options);
        Map<String, ConfigEntry> config = source.load();

        assertEquals(1, config.size());
        assertEquals("bar", config.get("custom.foo").getValue());

        JdbcUtil.execute(dataSource, "DROP TABLE my_custom_config");
    }

    @Test(expected = IllegalStateException.class)
    public void testLoadFailsFastWhenTableUnavailable() throws SQLException {
        DbConfigSource source = new DbConfigSource("DB-All", 100, dataSource);
        JdbcUtil.execute(dataSource, "DROP TABLE system_config");
        source.load();
    }

    /**
     * 变更探测监听测试。
     */
    @Test
    public void testWatcherTriggering() throws SQLException {
        // 轮询间隔设为 1 秒以加快测试速度
        DbConfigWatcher watcher = new DbConfigWatcher(dataSource, 1);

        AtomicBoolean triggered = new AtomicBoolean(false);
        watcher.watch(() -> triggered.set(true));

        // 等待初始轮询完成，建立基线
        ThreadUtil.sleep(1200);
        assertFalse("初始化阶段不应触发 changeSignal", triggered.get());

        // 修改数据库中的配置值
        JdbcUtil.execute(dataSource, "UPDATE system_config SET config_value = '9090' WHERE config_key = 'port'");

        // 等待下一轮轮询完成
        ThreadUtil.sleep(1500);

        assertTrue("数据库发生变更后，应触发 changeSignal 回调", triggered.get());

        watcher.destroy();
    }

    @Test
    public void testWatcherFailureKeepsBaselineAndRecordsError() throws SQLException {
        DbConfigWatcher watcher = new DbConfigWatcher(dataSource, 1);
        AtomicInteger triggerCount = new AtomicInteger();

        watcher.watch(triggerCount::incrementAndGet);

        ThreadUtil.sleep(1200);
        JdbcUtil.execute(dataSource, "DROP TABLE system_config");

        ThreadUtil.sleep(1200);
        assertEquals("查询失败时不应伪装成变更", 0, triggerCount.get());
        assertTrue("失败计数应递增", watcher.getFailureCount() > 0);
        assertNotNull("最近错误应可观测", watcher.getLastErrorMessage());

        watcher.destroy();
    }
}
