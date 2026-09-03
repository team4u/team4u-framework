package com.team4u.framework.flow.definition.property;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.PropertyPath;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.parser.SourceSpan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认属性访问编译器（Default Property Access Compiler）。
 *
 * <p>开箱即用支持 {@link Map} 键值访问与标准 JavaBean / POJO 属性访问（Getter / Setter / Public Field），
 * 具备反射访问元数据缓存与严格的诊断定位能力。</p>
 *
 * @author jay.wu
 */
public final class DefaultPropertyAccessCompiler implements PropertyAccessCompiler {

    public static final DefaultPropertyAccessCompiler INSTANCE = new DefaultPropertyAccessCompiler();

    private static final Map<String, PropertyAccessor> ACCESSOR_CACHE = new ConcurrentHashMap<String, PropertyAccessor>();

    @Override
    public CompiledReader compileReader(TypeRef rootType, PropertyPath path) {
        Objects.requireNonNull(rootType, "rootType must not be null");
        Objects.requireNonNull(path, "path must not be null");

        List<String> segments = path.segments();
        Class<?> raw = rootType.rawType();

        // 静态校验（若已知确切 POJO 类型而非 Map/Object）
        TypeRef currentType = rootType;
        if (raw != null && !Map.class.isAssignableFrom(raw) && !Object.class.equals(raw)) {
            Class<?> currClass = raw;
            for (String seg : segments) {
                PropertyAccessor acc = resolveAccessor(currClass, seg);
                if (acc == null) {
                    throw new FlowDiagnosticException(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_PROPERTY,
                            "Property '" + seg + "' not found on type: " + currClass.getName(),
                            path.span()));
                }
                if (!acc.isReadable()) {
                    throw new FlowDiagnosticException(new Diagnostic(
                            DiagnosticCodes.PROPERTY_NOT_READABLE,
                            "Property '" + seg + "' is not readable on type: " + currClass.getName(),
                            path.span()));
                }
                currClass = acc.propertyType();
            }
            currentType = TypeRef.of(currClass);
        } else {
            currentType = TypeRef.ANY;
        }

        final TypeRef finalResultType = currentType;
        return new CompiledReader() {
            @Override
            public TypeRef resultType() {
                return finalResultType;
            }

            @Override
            @SuppressWarnings("rawtypes")
            public Object read(Object root) {
                if (root == null) {
                    throw new FlowDiagnosticException(new Diagnostic(
                            DiagnosticCodes.PROPERTY_ACCESS_ERROR,
                            "Cannot read property '" + path.expression() + "' from null root object",
                            path.span()));
                }

                Object current = root;
                for (int i = 0; i < segments.size(); i++) {
                    String seg = segments.get(i);
                    if (current == null) {
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.PROPERTY_NULL_VALUE,
                                "Intermediate property is null in path: " + path.expression(),
                                path.span()));
                    }

                    if (current instanceof Map) {
                        Map map = (Map) current;
                        if (!map.containsKey(seg)) {
                            throw new FlowDiagnosticException(new Diagnostic(
                                    DiagnosticCodes.PROPERTY_NOT_FOUND,
                                    "Map key '" + seg + "' not found in: " + path.expression(),
                                    path.span()));
                        }
                        current = map.get(seg);
                    } else {
                        PropertyAccessor acc = resolveAccessor(current.getClass(), seg);
                        if (acc == null) {
                            throw new FlowDiagnosticException(new Diagnostic(
                                    DiagnosticCodes.PROPERTY_NOT_FOUND,
                                    "Property '" + seg + "' not found on instance of " + current.getClass().getName(),
                                    path.span()));
                        }
                        current = acc.get(current);
                        if (current == null && i < segments.size() - 1) {
                            throw new FlowDiagnosticException(new Diagnostic(
                                    DiagnosticCodes.PROPERTY_NULL_VALUE,
                                    "Intermediate property '" + seg + "' is null in path: " + path.expression(),
                                    path.span()));
                        }
                    }
                }
                return current;
            }
        };
    }

    @Override
    public CompiledWriter compileWriter(TypeRef rootType, PropertyPath path, TypeRef valueType) {
        Objects.requireNonNull(rootType, "rootType must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(valueType, "valueType must not be null");

        List<String> segments = path.segments();
        Class<?> raw = rootType.rawType();

        // 静态校验写回能力
        if (raw != null && !Map.class.isAssignableFrom(raw) && !Object.class.equals(raw)) {
            Class<?> currClass = raw;
            for (int i = 0; i < segments.size(); i++) {
                String seg = segments.get(i);
                PropertyAccessor acc = resolveAccessor(currClass, seg);
                if (acc == null) {
                    throw new FlowDiagnosticException(new Diagnostic(
                            DiagnosticCodes.UNKNOWN_PROPERTY,
                            "Property '" + seg + "' not found on type: " + currClass.getName(),
                            path.span()));
                }
                if (i == segments.size() - 1) {
                    if (!acc.isWritable()) {
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.PROPERTY_NOT_WRITABLE,
                                "Property '" + seg + "' is not writable on type: " + currClass.getName(),
                                path.span()));
                    }
                    if (valueType != TypeRef.ANY && valueType.rawType() != null
                            && !acc.propertyType().isAssignableFrom(valueType.rawType())
                            && !com.team4u.framework.flow.definition.type.ClassTypeRef.isBoxingCompatible(acc.propertyType(), valueType.rawType())) {
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.PROPERTY_TYPE_MISMATCH,
                                "Cannot write value of type " + valueType.typeName() + " to property '" + seg + "' of type " + acc.propertyType().getName(),
                                path.span()));
                    }
                }
                currClass = acc.propertyType();
            }
        }

        return new CompiledWriter() {
            @Override
            public TypeRef resultType() {
                return rootType;
            }

            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object write(Object root, Object value) {
                if (root == null) {
                    throw new FlowDiagnosticException(new Diagnostic(
                            DiagnosticCodes.PROPERTY_ACCESS_ERROR,
                            "Cannot write property '" + path.expression() + "' to null root object",
                            path.span()));
                }

                Object current = root;
                for (int i = 0; i < segments.size() - 1; i++) {
                    String seg = segments.get(i);
                    if (current instanceof Map) {
                        Map map = (Map) current;
                        Object next = map.get(seg);
                        if (next == null) {
                            next = new LinkedHashMap<String, Object>();
                            map.put(seg, next);
                        }
                        current = next;
                    } else {
                        PropertyAccessor acc = resolveAccessor(current.getClass(), seg);
                        if (acc == null) {
                            throw new FlowDiagnosticException(new Diagnostic(
                                    DiagnosticCodes.PROPERTY_NOT_FOUND,
                                    "Intermediate property '" + seg + "' not found on " + current.getClass().getName(),
                                    path.span()));
                        }
                        current = acc.get(current);
                        if (current == null) {
                            throw new FlowDiagnosticException(new Diagnostic(
                                    DiagnosticCodes.PROPERTY_NULL_VALUE,
                                    "Intermediate property '" + seg + "' is null on " + current.getClass().getName(),
                                    path.span()));
                        }
                    }
                }

                String lastSeg = segments.get(segments.size() - 1);
                if (current instanceof Map) {
                    ((Map) current).put(lastSeg, value);
                } else {
                    PropertyAccessor acc = resolveAccessor(current.getClass(), lastSeg);
                    if (acc == null) {
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.PROPERTY_NOT_FOUND,
                                "Property '" + lastSeg + "' not found on " + current.getClass().getName(),
                                path.span()));
                    }
                    if (!acc.isWritable()) {
                        throw new FlowDiagnosticException(new Diagnostic(
                                DiagnosticCodes.PROPERTY_NOT_WRITABLE,
                                "Property '" + lastSeg + "' is not writable on " + current.getClass().getName(),
                                path.span()));
                    }
                    acc.set(current, value);
                }

                return root;
            }
        };
    }

    private static PropertyAccessor resolveAccessor(Class<?> clazz, String propertyName) {
        String cacheKey = clazz.getName() + "#" + propertyName;
        PropertyAccessor cached = ACCESSOR_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String capitalized = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        Method getter = null;
        Method setter = null;

        // 尝试寻找 getter
        for (String prefix : new String[]{"get", "is"}) {
            try {
                Method m = clazz.getMethod(prefix + capitalized);
                if (!Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0) {
                    getter = m;
                    break;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }

        // 尝试寻找 setter
        if (getter != null) {
            Class<?> propType = getter.getReturnType();
            try {
                Method m = clazz.getMethod("set" + capitalized, propType);
                if (!Modifier.isStatic(m.getModifiers())) {
                    setter = m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        } else {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals("set" + capitalized)
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 1) {
                    setter = m;
                    break;
                }
            }
        }

        Field field = null;
        try {
            Field f = clazz.getField(propertyName);
            if (!Modifier.isStatic(f.getModifiers())) {
                field = f;
            }
        } catch (NoSuchFieldException ignored) {
        }

        if (getter == null && field == null && setter == null) {
            return null;
        }

        PropertyAccessor accessor = new PropertyAccessor(propertyName, getter, setter, field);
        ACCESSOR_CACHE.put(cacheKey, accessor);
        return accessor;
    }

    private static final class PropertyAccessor {
        private final String name;
        private final Method getter;
        private final Method setter;
        private final Field field;
        private final Class<?> propertyType;

        PropertyAccessor(String name, Method getter, Method setter, Field field) {
            this.name = name;
            this.getter = getter;
            this.setter = setter;
            this.field = field;
            if (getter != null) {
                this.propertyType = getter.getReturnType();
            } else if (field != null) {
                this.propertyType = field.getType();
            } else if (setter != null) {
                this.propertyType = setter.getParameterTypes()[0];
            } else {
                this.propertyType = Object.class;
            }
        }

        Class<?> propertyType() {
            return propertyType;
        }

        boolean isReadable() {
            return getter != null || field != null;
        }

        boolean isWritable() {
            return setter != null || (field != null && !Modifier.isFinal(field.getModifiers()));
        }

        Object get(Object target) {
            try {
                if (getter != null) {
                    return getter.invoke(target);
                }
                if (field != null) {
                    return field.get(target);
                }
                throw new IllegalStateException("Property has no getter or field: " + name);
            } catch (Exception e) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.PROPERTY_ACCESS_ERROR,
                        "Error reading property '" + name + "': " + e.getMessage(),
                        SourceSpan.UNKNOWN));
            }
        }

        void set(Object target, Object value) {
            try {
                if (setter != null) {
                    setter.invoke(target, value);
                    return;
                }
                if (field != null) {
                    field.set(target, value);
                    return;
                }
                throw new IllegalStateException("Property has no setter or field: " + name);
            } catch (Exception e) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.PROPERTY_ACCESS_ERROR,
                        "Error writing property '" + name + "': " + e.getMessage(),
                        SourceSpan.UNKNOWN));
            }
        }
    }
}
