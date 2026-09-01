package com.team4u.framework.flow.log;

import com.team4u.framework.mask.FastMasker;
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link TraceContext} 与 {@link TraceIgnore} 注解的上下文属性投影器实现。
 *
 * <p>特性与契约：
 * <ul>
 *   <li><b>类级别声明</b>：若类上标注了 {@link TraceContext}，默认提取该类及其所有父类的非 static、非 transient 字段；若字段标注了 {@link TraceIgnore} 则排除；</li>
 *   <li><b>字段级别声明</b>：若类上未标注 {@link TraceContext}，仅提取显式标注了 {@link TraceContext} 的字段（白名单模式）；</li>
 *   <li><b>别名映射</b>：字段注解的 {@link TraceContext#value()} 支持重命名输出属性键；</li>
 *   <li><b>自动掩码脱敏</b>：若字段标注了 {@link Mask}，自动应用 {@link FastMasker} 进行脱敏；</li>
 *   <li><b>无注解透传</b>：若类与字段均未标注任何追踪注解，则默认原样返回原始上下文对象；</li>
 *   <li><b>高性能缓存</b>：类字段反射元数据在首次访问后永久缓存至并发字典，运行期零反射解析开销。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public class AnnotatedContextProjector implements ContextProjector {

    public static final AnnotatedContextProjector INSTANCE = new AnnotatedContextProjector();

    private static final Map<Class<?>, ClassMetadata> CACHE = new ConcurrentHashMap<Class<?>, ClassMetadata>();

    private static final class FieldMeta {
        private final Field field;
        private final String outputName;
        private final MaskType maskType;

        FieldMeta(Field field, String outputName, MaskType maskType) {
            this.field = field;
            this.outputName = outputName;
            this.maskType = maskType;
            this.field.setAccessible(true);
        }
    }

    private static final class ClassMetadata {
        private final boolean annotated;
        private final List<FieldMeta> fields;

        ClassMetadata(boolean annotated, List<FieldMeta> fields) {
            this.annotated = annotated;
            this.fields = Collections.unmodifiableList(fields);
        }
    }

    @Override
    public Object project(Object context) {
        if (context == null) {
            return null;
        }

        if (context instanceof Map<?, ?>) {
            return context;
        }

        ClassMetadata metadata = CACHE.computeIfAbsent(context.getClass(), AnnotatedContextProjector::resolveMetadata);
        if (!metadata.annotated) {
            return context;
        }

        Map<String, Object> projected = new LinkedHashMap<String, Object>();
        for (FieldMeta meta : metadata.fields) {
            try {
                Object value = meta.field.get(context);
                if (meta.maskType != null && value != null) {
                    value = FastMasker.mask(String.valueOf(value), meta.maskType);
                }
                projected.put(meta.outputName, value);
            } catch (Exception ignored) {
            }
        }
        return projected;
    }

    private static ClassMetadata resolveMetadata(Class<?> clazz) {
        boolean classAnnotated = isClassAnnotated(clazz);
        List<Field> allFields = getAllFields(clazz);
        List<FieldMeta> matched = new ArrayList<FieldMeta>();
        boolean hasAnyFieldAnnotated = false;

        for (Field field : allFields) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }

            if (field.isAnnotationPresent(TraceIgnore.class)) {
                continue;
            }

            TraceContext fieldAnn = field.getAnnotation(TraceContext.class);
            if (fieldAnn != null) {
                hasAnyFieldAnnotated = true;
            }

            if (classAnnotated || fieldAnn != null) {
                String name = (fieldAnn != null && !fieldAnn.value().trim().isEmpty())
                        ? fieldAnn.value().trim()
                        : field.getName();
                Mask maskAnn = field.getAnnotation(Mask.class);
                MaskType maskType = maskAnn != null ? maskAnn.value() : null;
                matched.add(new FieldMeta(field, name, maskType));
            }
        }

        boolean active = classAnnotated || hasAnyFieldAnnotated;
        return new ClassMetadata(active, matched);
    }

    private static boolean isClassAnnotated(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            if (current.isAnnotationPresent(TraceContext.class)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<Field>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Field[] declared = current.getDeclaredFields();
            for (Field f : declared) {
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }
}
