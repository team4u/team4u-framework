package com.team4u.framework.lease.jdbc;

import org.junit.Assert;
import org.junit.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JdbcLeaseSchemaTest {

    private static final Set<String> REQUIRED_COLUMNS = new HashSet<String>(Arrays.asList(
            "task_id", "queue_name", "task_type", "payload", "deduplication_key", "status",
            "priority", "attempt_count", "worker_id", "lease_token", "lease_expires_at",
            "visible_at", "created_at", "updated_at", "version", "error_message",
            "attributes_json"));

    @Test
    public void testH2SchemaCreatesLogicalColumnsAndIndexes() throws SQLException {
        DataSource dataSource = JdbcLeaseBackendTestSupport.newDataSource();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            Assert.assertEquals(REQUIRED_COLUMNS, columns(metadata));
            assertIndex(metadata, "UK_LEASE_TASK_DEDUP", true,
                    "queue_name", "task_type", "deduplication_key");
            assertIndex(metadata, "IDX_LEASE_TASK_PENDING", false,
                    "queue_name", "status", "task_type", "visible_at", "priority",
                    "created_at", "task_id");
            assertIndex(metadata, "IDX_LEASE_TASK_EXPIRED", false,
                    "queue_name", "status", "task_type", "lease_expires_at", "priority",
                    "created_at", "task_id");
            assertIndex(metadata, "IDX_LEASE_TASK_QUERY", false,
                    "queue_name", "task_type", "status", "worker_id", "created_at", "task_id");
        }
    }

    @Test
    public void testMysqlSchemaUsesMysql8CompatibleDdl() throws IOException {
        String sql = resourceText("/schema/lease_task_mysql.sql");
        Assert.assertFalse(sql.toUpperCase(Locale.ROOT).contains("CREATE INDEX IF NOT EXISTS"));

        assertInlineIndex(sql, "UNIQUE KEY", "uk_lease_task_dedup",
                "queue_name", "task_type", "deduplication_key");
        assertInlineIndex(sql, "KEY", "idx_lease_task_pending",
                "queue_name", "status", "task_type", "visible_at", "priority", "created_at", "task_id");
        assertInlineIndex(sql, "KEY", "idx_lease_task_expired",
                "queue_name", "status", "task_type", "lease_expires_at", "priority", "created_at", "task_id");
        assertInlineIndex(sql, "KEY", "idx_lease_task_query",
                "queue_name", "task_type", "status", "worker_id", "created_at", "task_id");

        Assert.assertTrue(sql.contains("COLLATE utf8mb4_bin"));
        for (String column : new String[]{"task_id", "queue_name", "task_type",
                "deduplication_key"}) {
            Assert.assertTrue(column + " must use a binary collation",
                    columnDefinition(sql, column).contains("COLLATE utf8mb4_bin"));
        }
        Assert.assertTrue(sql.contains("ENGINE = InnoDB"));
    }

    private void assertInlineIndex(String sql, String indexKind, String indexName,
                                   String... columns) {
        Pattern pattern = Pattern.compile(indexKind + "\\s+" + Pattern.quote(indexName)
                + "\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql);
        Assert.assertTrue("missing inline index " + indexName, matcher.find());

        String[] actual = matcher.group(1).split(",");
        Assert.assertEquals("unexpected columns for " + indexName,
                columns.length, actual.length);
        for (int i = 0; i < columns.length; i++) {
            Assert.assertEquals(columns[i], actual[i].trim());
        }
    }

    private String columnDefinition(String sql, String column) {
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(column) + "\\s+.*$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(sql);
        Assert.assertTrue("missing column definition " + column, matcher.find());
        return matcher.group();
    }

    private Set<String> columns(DatabaseMetaData metadata) throws SQLException {
        Set<String> names = new HashSet<String>();
        try (ResultSet rs = metadata.getColumns(null, null, "LEASE_TASK", null)) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return names;
    }

    private void assertIndex(DatabaseMetaData metadata, String indexName, boolean unique,
                             String... expectedColumns) throws SQLException {
        Set<String> actual = new HashSet<String>();
        boolean found = false;
        boolean actualUnique = false;
        try (ResultSet rs = metadata.getIndexInfo(null, null, "LEASE_TASK", false, false)) {
            while (rs.next()) {
                if (!indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    continue;
                }
                found = true;
                actualUnique = !rs.getBoolean("NON_UNIQUE");
                actual.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        Assert.assertTrue("missing index: " + indexName, found);
        Assert.assertEquals("unexpected uniqueness for " + indexName, unique, actualUnique);
        Assert.assertEquals("unexpected columns for " + indexName,
                new HashSet<String>(Arrays.asList(expectedColumns)), actual);
    }

    private static String resourceText(String path) throws IOException {
        InputStream inputStream = JdbcLeaseSchemaTest.class.getResourceAsStream(path);
        Assert.assertNotNull(path, inputStream);
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            inputStream.close();
        }
    }
}
