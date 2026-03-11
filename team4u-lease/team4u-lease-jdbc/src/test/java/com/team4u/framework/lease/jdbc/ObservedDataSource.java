package com.team4u.framework.lease.jdbc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可观测的数据源装饰器
 * <p>
 * 该类通过对 {@link DataSource}、{@link Connection} 和 {@link PreparedStatement} 进行三层动态代理，
 * 拦截并统计 SQL 的执行行为。主要用于单元测试中验证“热点路径优化”，例如确保 `publishIfAbsent` 仅产生单次插入，
 * 或者 `acquire` 在无竞争时仅产生必要的查询和更新。
 */
final class ObservedDataSource {

    private final DataSource dataSource;
    private final AtomicInteger executionCount = new AtomicInteger();
    private final List<SqlInterceptor> interceptors = new CopyOnWriteArrayList<SqlInterceptor>();
    private final List<String> executedSql = new CopyOnWriteArrayList<String>();
    private ObservedDataSource(DataSource delegate) {
        this.dataSource = proxyDataSource(delegate);
    }

    static ObservedDataSource wrap(DataSource delegate) {
        return new ObservedDataSource(delegate);
    }

    DataSource dataSource() {
        return dataSource;
    }

    int executionCount() {
        return executionCount.get();
    }

    void resetCount() {
        executionCount.set(0);
    }

    void addInterceptor(SqlInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    List<String> executedSql() {
        return new ArrayList<String>(executedSql);
    }

    private DataSource proxyDataSource(final DataSource delegate) {
        return proxy(DataSource.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                try {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof Connection) {
                        return proxyConnection((Connection) result);
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        });
    }

    private Connection proxyConnection(final Connection delegate) {
        return proxy(Connection.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                try {
                    Object result = method.invoke(delegate, args);
                    String name = method.getName();
                    if ((result instanceof PreparedStatement)
                            && ("prepareStatement".equals(name) || "prepareCall".equals(name))
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String) {
                        return proxyPreparedStatement((PreparedStatement) result, (String) args[0]);
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        });
    }

    private PreparedStatement proxyPreparedStatement(final PreparedStatement delegate, final String sql) {
        return proxy(PreparedStatement.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                try {
                    String name = method.getName();
                    if ("execute".equals(name)
                            || "executeUpdate".equals(name)
                            || "executeQuery".equals(name)
                            || "executeLargeUpdate".equals(name)) {
                        beforeExecute(sql);
                    }
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        });
    }

    private void beforeExecute(String sql) throws SQLException {
        executionCount.incrementAndGet();
        String normalizedSql = normalize(sql);
        executedSql.add(normalizedSql);
        for (SqlInterceptor interceptor : interceptors) {
            interceptor.beforeExecute(normalizedSql);
        }
    }

    private String normalize(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    interface SqlInterceptor {
        void beforeExecute(String normalizedSql) throws SQLException;
    }
}
