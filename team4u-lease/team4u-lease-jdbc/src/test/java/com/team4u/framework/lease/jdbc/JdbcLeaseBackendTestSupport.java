package com.team4u.framework.lease.jdbc;

import cn.hutool.db.Db;
import cn.hutool.db.ds.simple.SimpleDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

final class JdbcLeaseBackendTestSupport {

    private static final String SCHEMA_RESOURCE = "schema/lease_task_mysql.sql";

    private JdbcLeaseBackendTestSupport() {
    }

    static DataSource newDataSource() {
        String dbName = "lease_" + System.nanoTime();
        String jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SimpleDataSource(jdbcUrl, "sa", "");
        try {
            Db.use(dataSource).execute("DROP TABLE IF EXISTS lease_task");
            initializeSchema(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize H2 schema", e);
        }
        return dataSource;
    }

    private static void initializeSchema(DataSource dataSource) throws SQLException {
        String schemaSql = loadSchemaSql();
        for (String statement : schemaSql.split(";")) {
            String sql = statement.trim();
            if (!sql.isEmpty()) {
                Db.use(dataSource).execute(sql);
            }
        }
    }

    private static String loadSchemaSql() {
        try (InputStream inputStream = JdbcLeaseBackendTestSupport.class.getClassLoader()
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("schema resource not found: " + SCHEMA_RESOURCE);
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load schema resource: " + SCHEMA_RESOURCE, e);
        }
    }
}
