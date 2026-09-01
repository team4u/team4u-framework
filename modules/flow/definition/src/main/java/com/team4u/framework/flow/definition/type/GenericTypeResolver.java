package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Resumed;

import java.lang.reflect.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 泛型反射类型推导解析器（Generic Type Resolver）。
 *
 * <p>用于从 Operation、Policy 及 PersistentPolicy 的实现类或接口中，
 * 沿继承与实现层级递归推导泛型参数对应的 {@link TypeRef}。</p>
 *
 * @author jay.wu
 */
public final class GenericTypeResolver {

    private static final ConcurrentMap<Class<?>, TypeRef[]> OPERATION_CACHE =
            new ConcurrentHashMap<Class<?>, TypeRef[]>();
    private static final ConcurrentMap<Class<?>, TypeRef> POLICY_CACHE =
            new ConcurrentHashMap<Class<?>, TypeRef>();
    private static final ConcurrentMap<Class<?>, TypeRef[]> PERSISTENT_POLICY_CACHE =
            new ConcurrentHashMap<Class<?>, TypeRef[]>();

    private GenericTypeResolver() {
    }

    /**
     * 递归解析 {@link Operation} 实现类或接口的入参 {@code I} 与出参 {@code O} 类型引用。
     *
     * @param type 目标类型 Class
     * @return 包含入参和出参的类型引用数组 [inputType, outputType]（若无法解析则元素安全回退为 {@link TypeRef#ANY}）
     */
    public static TypeRef[] resolveOperationTypes(Class<?> type) {
        if (type == null) {
            return new TypeRef[]{TypeRef.ANY, TypeRef.ANY};
        }
        TypeRef[] cached = OPERATION_CACHE.get(type);
        if (cached != null) {
            return cached.clone();
        }
        TypeRef[] resolved = resolveGenericTypeArguments(type, Operation.class, 2);
        OPERATION_CACHE.putIfAbsent(type, resolved);
        return resolved.clone();
    }

    /**
     * 递归解析 {@link Policy} 实现类或接口的策略键 {@code K} 类型引用。
     *
     * @param type 目标类型 Class
     * @return 策略键类型引用（若无法解析则安全回退为 {@link TypeRef#ANY}）
     */
    public static TypeRef resolvePolicyKeyType(Class<?> type) {
        if (type == null) {
            return TypeRef.ANY;
        }
        TypeRef cached = POLICY_CACHE.get(type);
        if (cached != null) {
            return cached;
        }
        TypeRef[] resolved = resolveGenericTypeArguments(type, Policy.class, 1);
        TypeRef result = resolved[0];
        POLICY_CACHE.putIfAbsent(type, result);
        return result;
    }

    /**
     * 递归解析 {@link PersistentPolicy} 实现类或接口的策略键 {@code K} 与状态 {@code S} 类型引用。
     *
     * @param type 目标类型 Class
     * @return 包含策略键和状态的类型引用数组 [keyType, stateType]（若无法解析则元素安全回退为 {@link TypeRef#ANY}）
     */
    public static TypeRef[] resolvePersistentPolicyTypes(Class<?> type) {
        if (type == null) {
            return new TypeRef[]{TypeRef.ANY, TypeRef.ANY};
        }
        TypeRef[] cached = PERSISTENT_POLICY_CACHE.get(type);
        if (cached != null) {
            return cached.clone();
        }
        TypeRef[] resolved = resolveGenericTypeArguments(type, PersistentPolicy.class, 2);
        PERSISTENT_POLICY_CACHE.putIfAbsent(type, resolved);
        return resolved.clone();
    }

    /**
     * 递归解析目标接口的泛型参数类型引用。
     *
     * @param sourceClass     待分析的源类型 Class
     * @param targetInterface 目标接口 Class
     * @param expectedCount   预期的泛型参数数量
     * @return 泛型参数 TypeRef 数组
     */
    public static TypeRef[] resolveGenericTypeArguments(
            Class<?> sourceClass,
            Class<?> targetInterface,
            int expectedCount) {
        TypeRef[] defaultResult = new TypeRef[expectedCount];
        for (int i = 0; i < expectedCount; i++) {
            defaultResult[i] = TypeRef.ANY;
        }

        if (sourceClass == null || targetInterface == null || !targetInterface.isAssignableFrom(sourceClass)) {
            return defaultResult;
        }

        Type[] resolvedTypes = findGenericTypeArguments(sourceClass, targetInterface, Collections.emptyMap());
        if (resolvedTypes == null) {
            return defaultResult;
        }

        TypeRef[] result = new TypeRef[expectedCount];
        for (int i = 0; i < expectedCount; i++) {
            if (i < resolvedTypes.length && resolvedTypes[i] != null) {
                result[i] = toTypeRef(resolvedTypes[i]);
            } else {
                result[i] = TypeRef.ANY;
            }
        }
        return result;
    }

    private static Type[] findGenericTypeArguments(
            Type currentType,
            Class<?> targetInterface,
            Map<TypeVariable<?>, Type> typeVarMap) {
        if (currentType == null) {
            return null;
        }

        if (currentType instanceof Class<?>) {
            Class<?> clazz = (Class<?>) currentType;
            if (!targetInterface.isAssignableFrom(clazz)) {
                return null;
            }
            if (clazz.equals(targetInterface)) {
                return new Type[targetInterface.getTypeParameters().length];
            }

            // 优先检查泛型父类
            Type genericSuperclass = clazz.getGenericSuperclass();
            if (genericSuperclass != null) {
                Class<?> rawSuper = getRawType(genericSuperclass);
                if (rawSuper != null && targetInterface.isAssignableFrom(rawSuper)) {
                    Type[] res = findGenericTypeArguments(genericSuperclass, targetInterface, typeVarMap);
                    if (res != null) {
                        return res;
                    }
                }
            }

            // 检查实现的泛型接口
            for (Type genericInterface : clazz.getGenericInterfaces()) {
                Class<?> rawIntf = getRawType(genericInterface);
                if (rawIntf != null && targetInterface.isAssignableFrom(rawIntf)) {
                    Type[] res = findGenericTypeArguments(genericInterface, targetInterface, typeVarMap);
                    if (res != null) {
                        return res;
                    }
                }
            }
            return null;
        }

        if (currentType instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) currentType;
            Type raw = pt.getRawType();
            if (!(raw instanceof Class<?>)) {
                return null;
            }
            Class<?> rawClass = (Class<?>) raw;
            if (!targetInterface.isAssignableFrom(rawClass)) {
                return null;
            }

            Type[] actualArgs = pt.getActualTypeArguments();
            TypeVariable<?>[] typeParams = rawClass.getTypeParameters();
            Map<TypeVariable<?>, Type> nextMap = new HashMap<TypeVariable<?>, Type>(typeVarMap);
            for (int i = 0; i < typeParams.length && i < actualArgs.length; i++) {
                Type resolved = resolveTypeVariable(actualArgs[i], typeVarMap);
                nextMap.put(typeParams[i], resolved);
            }

            if (rawClass.equals(targetInterface)) {
                Type[] result = new Type[actualArgs.length];
                for (int i = 0; i < actualArgs.length; i++) {
                    result[i] = resolveTypeVariable(actualArgs[i], nextMap);
                }
                return result;
            }

            // 检查泛型父类
            Type genericSuperclass = rawClass.getGenericSuperclass();
            if (genericSuperclass != null) {
                Class<?> rawSuper = getRawType(genericSuperclass);
                if (rawSuper != null && targetInterface.isAssignableFrom(rawSuper)) {
                    Type[] res = findGenericTypeArguments(genericSuperclass, targetInterface, nextMap);
                    if (res != null) {
                        return res;
                    }
                }
            }

            // 检查实现的泛型接口
            for (Type genericInterface : rawClass.getGenericInterfaces()) {
                Class<?> rawIntf = getRawType(genericInterface);
                if (rawIntf != null && targetInterface.isAssignableFrom(rawIntf)) {
                    Type[] res = findGenericTypeArguments(genericInterface, targetInterface, nextMap);
                    if (res != null) {
                        return res;
                    }
                }
            }
            return null;
        }

        return null;
    }

    private static Type resolveTypeVariable(Type type, Map<TypeVariable<?>, Type> typeVarMap) {
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> tv = (TypeVariable<?>) type;
            if (typeVarMap.containsKey(tv)) {
                Type mapped = typeVarMap.get(tv);
                if (mapped != type && mapped != null) {
                    return resolveTypeVariable(mapped, typeVarMap);
                }
                return mapped;
            }
            Type[] bounds = tv.getBounds();
            if (bounds != null && bounds.length > 0 && bounds[0] != Object.class) {
                return resolveTypeVariable(bounds[0], typeVarMap);
            }
            return type;
        }

        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type[] args = pt.getActualTypeArguments();
            Type[] resolvedArgs = new Type[args.length];
            boolean modified = false;
            for (int i = 0; i < args.length; i++) {
                resolvedArgs[i] = resolveTypeVariable(args[i], typeVarMap);
                if (resolvedArgs[i] != args[i]) {
                    modified = true;
                }
            }
            if (modified) {
                return new ParameterizedTypeImpl(resolvedArgs, pt.getOwnerType(), pt.getRawType());
            }
            return pt;
        }

        if (type instanceof WildcardType) {
            WildcardType wt = (WildcardType) type;
            Type[] upperBounds = wt.getUpperBounds();
            if (upperBounds != null && upperBounds.length > 0) {
                return resolveTypeVariable(upperBounds[0], typeVarMap);
            }
        }

        if (type instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType) type;
            Type componentType = resolveTypeVariable(gat.getGenericComponentType(), typeVarMap);
            if (componentType instanceof Class<?>) {
                return Array.newInstance((Class<?>) componentType, 0).getClass();
            }
        }

        return type;
    }

    /**
     * 将反射 {@link Type} 转换为流程类型系统的 {@link TypeRef}。
     *
     * @param type 反射类型
     * @return 对应的 TypeRef
     */
    public static TypeRef toTypeRef(Type type) {
        if (type == null) {
            return TypeRef.ANY;
        }
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            if (clazz == Object.class) {
                return TypeRef.ANY;
            }
            return TypeRef.of(clazz);
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type rawType = pt.getRawType();
            if (rawType instanceof Class<?>) {
                Class<?> rawClass = (Class<?>) rawType;
                Type[] args = pt.getActualTypeArguments();
                if (rawClass == Resumed.class && args.length == 2) {
                    return TypeRef.resumed(toTypeRef(args[0]), toTypeRef(args[1]));
                }
                if (rawClass == Recovery.class && args.length == 1) {
                    return TypeRef.recovery(toTypeRef(args[0]));
                }
                if (rawClass == Object.class) {
                    return TypeRef.ANY;
                }
                return TypeRef.of(rawClass);
            }
        }
        if (type instanceof WildcardType) {
            WildcardType wt = (WildcardType) type;
            Type[] upper = wt.getUpperBounds();
            if (upper != null && upper.length > 0) {
                return toTypeRef(upper[0]);
            }
            return TypeRef.ANY;
        }
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> tv = (TypeVariable<?>) type;
            Type[] bounds = tv.getBounds();
            if (bounds != null && bounds.length > 0 && bounds[0] != Object.class) {
                return toTypeRef(bounds[0]);
            }
            return TypeRef.ANY;
        }
        if (type instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType) type;
            Type comp = gat.getGenericComponentType();
            TypeRef compRef = toTypeRef(comp);
            if (compRef.rawType() != null) {
                return TypeRef.of(Array.newInstance(compRef.rawType(), 0).getClass());
            }
            return TypeRef.ANY;
        }
        return TypeRef.ANY;
    }

    private static Class<?> getRawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class<?>) {
                return (Class<?>) raw;
            }
        }
        return null;
    }

    private static final class ParameterizedTypeImpl implements ParameterizedType {
        private final Type[] actualTypeArguments;
        private final Type ownerType;
        private final Type rawType;

        ParameterizedTypeImpl(Type[] actualTypeArguments, Type ownerType, Type rawType) {
            this.actualTypeArguments = actualTypeArguments;
            this.ownerType = ownerType;
            this.rawType = rawType;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments;
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }
    }
}
