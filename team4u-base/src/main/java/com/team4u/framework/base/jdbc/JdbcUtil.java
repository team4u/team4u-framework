package com.team4u.framework.base.jdbc;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.base.convert.TypeConversionException;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易 JDBC 工具类
 * <p>
 * 提供基于数据源的基础数据库操作，支持简单的查询与 SQL 执行。
 *
 * @author jay.wu
 */
public class JdbcUtil {

    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 执行查询并返回结果列表
     * <p>
     * 自动处理连接的打开与关闭。查询结果中的每一行被封装为一个 {@link Map}，
     * 其中 Key 为小写的列名（或别名），Value 为对应列的值。
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 查询语句（可带 ? 占位符）
     * @param params     SQL 语句中的参数列表，按顺序对应占位符
     * @return 包含所有查询结果行的列表，若无结果则返回空列表
     * @throws SQLException 如果数据库访问出错
     */
    public static List<Map<String, Object>> query(DataSource dataSource, String sql, Object... params)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        // 使用 getColumnLabel 获取别名，并转换为小写作为 Key
                        row.put(metaData.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    /**
     * 查询单条记录并映射为 Map
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 查询语句
     * @param params     SQL 语句中的参数列表
     * @return 结果行 Map，若无结果则返回 null
     * @throws SQLException 如果数据库访问出错
     */
    public static Map<String, Object> queryOneMap(DataSource dataSource, String sql, Object... params)
            throws SQLException {
        List<Map<String, Object>> list = query(dataSource, sql, params);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询单个值
     * <p>
     * 适合 count(*)、max(id) 这种场景。
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 查询语句
     * @param type       目标类型的 Class
     * @param params     SQL 语句中的参数列表
     * @param <T>        目标类型
     * @return 查询结果值，若无结果则返回 null
     * @throws SQLException 如果数据库访问出错
     */
    public static <T> T queryScalar(DataSource dataSource, String sql, Class<T> type, Object... params)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object value = rs.getObject(1);
                    return convertScalarValue(value, type, sql);
                }
                return null;
            }
        }
    }

    /**
     * 查询多条记录并映射为实体列表
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 查询语句
     * @param clazz      实体的 Class
     * @param params     SQL 语句中的参数列表
     * @param <T>        实体类型
     * @return 包含所有查询结果实体的列表，若无结果则返回空列表
     * @throws SQLException 如果数据库访问出错
     */
    public static <T> List<T> queryList(DataSource dataSource, String sql, Class<T> clazz, Object... params)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    T obj = mapRowToBean(rs, metaData, columnCount, clazz);
                    result.add(obj);
                }
                return result;
            }
        }
    }

    /**
     * 查询单条记录并映射为实体
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 查询语句
     * @param clazz      实体的 Class
     * @param params     SQL 语句中的参数列表
     * @param <T>        实体类型
     * @return 查询结果实体，若无结果则返回 null
     * @throws SQLException 如果数据库访问出错
     */
    public static <T> T queryOne(DataSource dataSource, String sql, Class<T> clazz, Object... params)
            throws SQLException {
        List<T> list = queryList(dataSource, sql, clazz, params);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 根据对象自动构建并执行插入语句
     *
     * @param dataSource 数据库连接池或数据源
     * @param tableName  表名
     * @param bean       实体对象
     * @return 受到影响的行数
     * @throws SQLException 如果数据库访问出错
     */
    public static int insert(DataSource dataSource, String tableName, Object bean) throws SQLException {
        InsertBuilder builder = createInsertBuilder(tableName, bean);
        return execute(dataSource, builder.getSql(), builder.getParams());
    }

    /**
     * 根据对象自动构建、执行插入语句并返回自增主键
     *
     * @param dataSource 数据库连接池或数据源
     * @param tableName  表名
     * @param bean       实体对象
     * @return 生成的自增主键值，若无则返回 null
     * @throws SQLException 如果数据库访问出错
     */
    public static Long insertAndReturnKey(DataSource dataSource, String tableName, Object bean) throws SQLException {
        InsertBuilder builder = createInsertBuilder(tableName, bean);
        return insertAndReturnKey(dataSource, builder.getSql(), builder.getParams());
    }

    /**
     * 执行更新、插入或删除等非查询 SQL 语句
     * <p>
     * 自动处理连接的打开与关闭。
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 语句（可带 ? 占位符）
     * @param params     SQL 语句中的参数列表，按顺序对应占位符
     * @return 受到影响的行数
     * @throws SQLException 如果数据库访问出错
     */
    public static int execute(DataSource dataSource, String sql, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * 执行插入并返回自增主键
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 插入语句
     * @param params     SQL 语句中的参数列表
     * @return 生成的自增主键值，若无则返回 null
     * @throws SQLException 如果数据库访问出错
     */
    public static Long insertAndReturnKey(DataSource dataSource, String sql, Object... params)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setParameters(ps, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return null;
            }
        }
    }

    /**
     * 根据实体字段自动绑定参数并执行 SQL
     *
     * @param dataSource  数据库连接池或数据源
     * @param sql         待执行的 SQL 语句
     * @param bean        实体对象
     * @param firstField  第一个映射到 SQL 占位符的实体属性名
     * @param otherFields 其他映射到 SQL 占位符的实体属性名列表
     * @return 受到影响的行数
     * @throws SQLException 如果数据库访问出错
     */
    public static int executeWithBean(DataSource dataSource, String sql, Object bean, String firstField, String... otherFields)
            throws SQLException {
        String[] fields = new String[otherFields.length + 1];
        fields[0] = firstField;
        System.arraycopy(otherFields, 0, fields, 1, otherFields.length);
        Object[] params = extractFieldValues(bean, fields);
        return execute(dataSource, sql, params);
    }

    /**
     * 设置 PreparedStatement 的占位符参数
     *
     * @param ps     PreparedStatement 对象
     * @param params 参数列表
     * @throws SQLException 如果设置参数出错
     */
    private static void setParameters(PreparedStatement ps, Object... params) throws SQLException {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }
    }

    /**
     * 根据对象的字段创建 InsertBuilder
     */
    private static InsertBuilder createInsertBuilder(String tableName, Object bean) {
        InsertBuilder builder = new InsertBuilder(tableName);
        Class<?> clazz = bean.getClass();
        Field[] fields = getAllFields(clazz);
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                Object value = field.get(bean);
                if (value != null) {
                    builder.column(toUnderlineCase(field.getName()), value);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("读取字段值失败: " + field.getName(), e);
            }
        }
        return builder;
    }

    /**
     * 获取类及其所有父类的字段
     */
    private static Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                // 排除 static 字段和 synthetic 字段
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    /**
     * 驼峰命名转下划线命名
     */
    private static String toUnderlineCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将 ResultSet 的当前行映射为实体
     */
    private static <T> T mapRowToBean(ResultSet rs, ResultSetMetaData metaData, int columnCount, Class<T> clazz)
            throws SQLException {
        try {
            T obj = clazz.getDeclaredConstructor().newInstance();

            for (int i = 1; i <= columnCount; i++) {
                String columnLabel = metaData.getColumnLabel(i);
                Object value = rs.getObject(i);

                Field field = findField(clazz, columnLabel);
                if (field == null) {
                    continue;
                }

                field.setAccessible(true);
                field.set(obj, convertFieldValue(value, field, clazz));
            }

            return obj;
        } catch (Exception e) {
            throw new SQLException("映射实体失败: " + clazz.getName(), e);
        }
    }

    /**
     * 在实体类及其动态超类中查找匹配的字段（支持下划线转驼峰）
     */
    private static Field findField(Class<?> clazz, String columnLabel) {
        Map<String, Field> classCache = FIELD_CACHE.computeIfAbsent(clazz, c -> new ConcurrentHashMap<>());
        return classCache.computeIfAbsent(columnLabel.toLowerCase(), label -> {
            String fieldName = toCamelCase(label);
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getName().equalsIgnoreCase(fieldName)) {
                        field.setAccessible(true);
                        return field;
                    }
                }
                current = current.getSuperclass();
            }
            return null;
        });
    }

    /**
     * 下划线命名转驼峰命名
     */
    private static String toCamelCase(String name) {
        if (name == null || name.isEmpty() || !name.contains("_")) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static <T> T convertScalarValue(Object value, Class<T> targetType, String sql) throws SQLException {
        if (value == null) {
            return null;
        }

        if (targetType.isInstance(value)) {
            return targetType.cast(value);
        }

        try {
            T converted = ConvertUtil.convert(targetType, value);
            if (converted != null) {
                return converted;
            }
        } catch (TypeConversionException e) {
            throw buildScalarConversionException(sql, targetType, value, e);
        }

        throw buildScalarConversionException(sql, targetType, value, null);
    }

    private static Object convertFieldValue(Object value, Field field, Class<?> beanType) throws SQLException {
        if (value == null) {
            return null;
        }

        Class<?> fieldType = field.getType();
        if (fieldType.isInstance(value)) {
            return value;
        }

        try {
            Object converted = ConvertUtil.convert(field.getGenericType(), value);
            if (converted != null) {
                return converted;
            }
        } catch (TypeConversionException e) {
            throw new SQLException("字段转换失败: bean=" + beanType.getName()
                    + ", field=" + field.getName()
                    + ", targetType=" + field.getGenericType().getTypeName()
                    + ", sourceType=" + value.getClass().getName()
                    + ", source=" + summarizeValue(value), e);
        }

        throw new SQLException("字段转换失败: bean=" + beanType.getName()
                + ", field=" + field.getName()
                + ", targetType=" + field.getGenericType().getTypeName()
                + ", sourceType=" + value.getClass().getName()
                + ", source=" + summarizeValue(value));
    }

    private static <T> SQLException buildScalarConversionException(String sql,
                                                                   Class<T> targetType,
                                                                   Object value,
                                                                   Throwable cause) {
        return new SQLException("标量转换失败: sql=" + sql
                + ", targetType=" + targetType.getName()
                + ", sourceType=" + value.getClass().getName()
                + ", source=" + summarizeValue(value), cause);
    }

    private static String summarizeValue(Object value) {
        String text = String.valueOf(value);
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 117) + "...";
    }

    /**
     * 从实体对象中提取指定字段的值
     */
    private static Object[] extractFieldValues(Object bean, String... fields) {
        Object[] values = new Object[fields.length];
        Class<?> clazz = bean.getClass();
        for (int i = 0; i < fields.length; i++) {
            try {
                Field field = getField(clazz, fields[i]);
                field.setAccessible(true);
                values[i] = field.get(bean);
            } catch (Exception e) {
                throw new RuntimeException("获取字段值失败: " + fields[i], e);
            }
        }
        return values;
    }

    /**
     * 获取类及其父类的字段（不区分大小写）
     */
    private static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equalsIgnoreCase(fieldName)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException(fieldName);
    }
}
