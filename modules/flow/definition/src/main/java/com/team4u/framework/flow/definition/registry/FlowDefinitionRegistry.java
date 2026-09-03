package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.api.ContextualJoinStrategy;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.type.ClassTypeRef;
import com.team4u.framework.flow.definition.type.GenericTypeResolver;
import com.team4u.framework.flow.definition.type.TypeCodec;
import com.team4u.framework.flow.definition.type.TypeCodecs;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.spi.BindingResolver;
import com.team4u.framework.flow.spi.OperationResolver;
import com.team4u.framework.flow.definition.property.DefaultPropertyAccessCompiler;
import com.team4u.framework.flow.definition.property.PropertyAccessCompiler;
import lombok.Getter;
import lombok.experimental.Accessors;

import com.team4u.framework.base.util.ServiceLoaderUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 流程定义外部符号注册表（Flow Definition Registry）。
 *
 * <p>统一管理流程 DSL 中引用的所有 Operation、Subflow、Policy、Projector、Merger、KeyProjection、Join、
 * ResumePoint 及 TypeCodec 映射关系，实现 DSL 符号与 Java 类/Bean 的解耦。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class FlowDefinitionRegistry {

    private final Map<String, FlowDefinition> subflows;
    private final Map<String, OperationDescriptor> operations;
    private final Map<String, PolicyDescriptor> policies;
    private final Map<String, PolicyProvider> policyProviders;
    private final Map<String, ProjectorDescriptor> projectors;
    private final Map<String, MergerDescriptor> mergers;
    private final Map<String, KeyProjectionDescriptor> keyProjections;
    private final Map<String, JoinDescriptor> joins;
    private final Map<String, ResumeDescriptor> resumePoints;
    private final Map<TypeRef, TypeCodec<?>> typeCodecs;
    private final BindingResolver fallbackResolver;
    private final PropertyAccessCompiler propertyAccessCompiler;
    private final Map<String, TypeRef> typeAliases;
    private final TypeRef initialInputType;

    private final ConcurrentMap<String, OperationDescriptor> dynamicOperations =
            new ConcurrentHashMap<String, OperationDescriptor>();
    private final ConcurrentMap<String, PolicyDescriptor> dynamicPolicies =
            new ConcurrentHashMap<String, PolicyDescriptor>();

    private FlowDefinitionRegistry(Builder builder) {
        this.subflows = Collections.unmodifiableMap(new LinkedHashMap<String, FlowDefinition>(builder.subflows));
        this.operations = Collections.unmodifiableMap(new LinkedHashMap<String, OperationDescriptor>(builder.operations));
        this.policies = Collections.unmodifiableMap(new LinkedHashMap<String, PolicyDescriptor>(builder.policies));
        this.policyProviders = Collections.unmodifiableMap(new LinkedHashMap<String, PolicyProvider>(builder.policyProviders));
        this.projectors = Collections.unmodifiableMap(new LinkedHashMap<String, ProjectorDescriptor>(builder.projectors));
        this.mergers = Collections.unmodifiableMap(new LinkedHashMap<String, MergerDescriptor>(builder.mergers));
        this.keyProjections = Collections.unmodifiableMap(new LinkedHashMap<String, KeyProjectionDescriptor>(builder.keyProjections));
        this.joins = Collections.unmodifiableMap(new LinkedHashMap<String, JoinDescriptor>(builder.joins));
        this.resumePoints = Collections.unmodifiableMap(new LinkedHashMap<String, ResumeDescriptor>(builder.resumePoints));
        this.typeCodecs = Collections.unmodifiableMap(new LinkedHashMap<TypeRef, TypeCodec<?>>(builder.typeCodecs));
        this.fallbackResolver = builder.fallbackResolver;
        this.propertyAccessCompiler = builder.propertyAccessCompiler;
        this.typeAliases = Collections.unmodifiableMap(new LinkedHashMap<String, TypeRef>(builder.typeAliases));
        this.initialInputType = builder.initialInputType;
    }

    public static Builder builder() {
        Builder builder = new Builder();
        for (FlowDefinitionExtension extension : ServiceLoaderUtil.loadAvailableList(FlowDefinitionExtension.class)) {
            builder.apply(extension);
        }
        return builder;
    }

    public static Builder builder(FlowDefinitionRegistry base) {
        if (base == null) {
            return builder();
        }
        return base.toBuilder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.subflows(this.subflows);
        builder.operations(this.operations);
        builder.policies(this.policies);
        builder.policyProviders(this.policyProviders);
        builder.projectors(this.projectors);
        builder.mergers(this.mergers);
        builder.keyProjections(this.keyProjections);
        builder.joins(this.joins);
        builder.resumePoints(this.resumePoints);
        builder.typeCodecs(this.typeCodecs);
        builder.fallbackResolver(this.fallbackResolver);
        builder.propertyAccessCompiler(this.propertyAccessCompiler);
        for (Map.Entry<String, TypeRef> entry : this.typeAliases.entrySet()) {
            builder.type(entry.getKey(), entry.getValue());
        }
        builder.initialInputType(this.initialInputType);
        return builder;
    }

    public PropertyAccessCompiler propertyAccessCompiler() {
        return propertyAccessCompiler;
    }

    public TypeRef typeAlias(String alias) {
        return typeAliases.get(alias);
    }

    public Map<String, TypeRef> typeAliases() {
        return typeAliases;
    }

    public TypeRef initialInputType() {
        return initialInputType;
    }

    public static FlowDefinitionRegistry empty() {
        return new Builder().build();
    }

    public FlowDefinition subflow(String id) {
        if (id == null) {
            return null;
        }
        return subflows.get(id);
    }

    @SuppressWarnings("unchecked")
    public OperationDescriptor operation(String id) {
        if (id == null) {
            return null;
        }
        OperationDescriptor descriptor = operations.get(id);
        if (descriptor != null) {
            return descriptor;
        }
        if (fallbackResolver != null) {
            OperationDescriptor dynamic = dynamicOperations.get(id);
            if (dynamic != null) {
                return dynamic;
            }
            try {
                Object resolved = fallbackResolver.resolve(id);
                if (resolved instanceof Operation) {
                    Operation<?, ?> instance = (Operation<?, ?>) resolved;
                    Class<?> implClass = fallbackResolver.implementationClass(resolved);
                    TypeRef[] types = GenericTypeResolver.resolveOperationTypes(implClass);
                    if (types[0] == TypeRef.ANY && types[1] == TypeRef.ANY && implClass != resolved.getClass()) {
                        types = GenericTypeResolver.resolveOperationTypes(resolved.getClass());
                    }
                    Class<? extends Operation<?, ?>> contractClass = (Class<? extends Operation<?, ?>>)
                            (Operation.class.isAssignableFrom(implClass) ? implClass : resolved.getClass());
                    OperationDescriptor fallbackDesc = OperationDescriptor.builder()
                            .id(id)
                            .contract(contractClass)
                            .instance(instance)
                            .inputType(types[0])
                            .outputType(types[1])
                            .build();
                    dynamicOperations.putIfAbsent(id, fallbackDesc);
                    return dynamicOperations.get(id);
                }
            } catch (Exception ignored) {
                // 回退解析失败则忽略异常并返回 null
            }
        }
        return null;
    }

    public PolicyDescriptor policy(String id) {
        if (id == null) {
            return null;
        }
        PolicyDescriptor descriptor = policies.get(id);
        if (descriptor != null) {
            return descriptor;
        }
        PolicyProvider provider = policyProviders.get(id);
        if (provider != null) {
            return provider.descriptor();
        }
        if (fallbackResolver != null) {
            PolicyDescriptor dynamic = dynamicPolicies.get(id);
            if (dynamic != null) {
                return dynamic;
            }
            try {
                Object resolved = fallbackResolver.resolve(id);
                if (resolved instanceof PersistentPolicy) {
                    PersistentPolicy<?, ?> instance = (PersistentPolicy<?, ?>) resolved;
                    Class<?> implClass = fallbackResolver.implementationClass(resolved);
                    TypeRef[] types = GenericTypeResolver.resolvePersistentPolicyTypes(implClass);
                    if (types[0] == TypeRef.ANY && types[1] == TypeRef.ANY && implClass != resolved.getClass()) {
                        types = GenericTypeResolver.resolvePersistentPolicyTypes(resolved.getClass());
                    }
                    Class<?> contractClass = PersistentPolicy.class.isAssignableFrom(implClass) ? implClass : resolved.getClass();
                    PolicyDescriptor fallbackDesc = PolicyDescriptor.builder()
                            .id(id)
                            .contract(contractClass)
                            .instance(instance)
                            .keyType(types[0])
                            .persistent(true)
                            .build();
                    dynamicPolicies.putIfAbsent(id, fallbackDesc);
                    return dynamicPolicies.get(id);
                } else if (resolved instanceof Policy) {
                    Policy<?> instance = (Policy<?>) resolved;
                    Class<?> implClass = fallbackResolver.implementationClass(resolved);
                    TypeRef keyType = GenericTypeResolver.resolvePolicyKeyType(implClass);
                    if (keyType == TypeRef.ANY && implClass != resolved.getClass()) {
                        keyType = GenericTypeResolver.resolvePolicyKeyType(resolved.getClass());
                    }
                    Class<?> contractClass = Policy.class.isAssignableFrom(implClass) ? implClass : resolved.getClass();
                    PolicyDescriptor fallbackDesc = PolicyDescriptor.builder()
                            .id(id)
                            .contract(contractClass)
                            .instance(instance)
                            .keyType(keyType)
                            .persistent(false)
                            .build();
                    dynamicPolicies.putIfAbsent(id, fallbackDesc);
                    return dynamicPolicies.get(id);
                }
            } catch (Exception ignored) {
                // 回退解析失败则忽略异常并返回 null
            }
        }
        return null;
    }

    public PolicyProvider policyProvider(String id) {
        return policyProviders.get(id);
    }

    public ProjectorDescriptor projector(String id) {
        return projectors.get(id);
    }

    public MergerDescriptor merger(String id) {
        return mergers.get(id);
    }

    public KeyProjectionDescriptor keyProjection(String id) {
        return keyProjections.get(id);
    }

    public JoinDescriptor join(String id) {
        return joins.get(id);
    }

    public ResumeDescriptor resumePoint(String id) {
        return resumePoints.get(id);
    }

    @SuppressWarnings("unchecked")
    public <T> TypeCodec<T> typeCodec(TypeRef typeRef) {
        if (typeRef != null) {
            TypeCodec<?> custom = typeCodecs.get(typeRef);
            if (custom != null) {
                return (TypeCodec<T>) custom;
            }
        }
        return (TypeCodec<T>) TypeCodecs.forType(typeRef);
    }

    /**
     * 注册表构建器。
     */
    public static final class Builder {
        private final Map<String, FlowDefinition> subflows = new LinkedHashMap<String, FlowDefinition>();
        private final Map<String, OperationDescriptor> operations = new LinkedHashMap<String, OperationDescriptor>();
        private final Map<String, PolicyDescriptor> policies = new LinkedHashMap<String, PolicyDescriptor>();
        private final Map<String, PolicyProvider> policyProviders = new LinkedHashMap<String, PolicyProvider>();
        private final Map<String, ProjectorDescriptor> projectors = new LinkedHashMap<String, ProjectorDescriptor>();
        private final Map<String, MergerDescriptor> mergers = new LinkedHashMap<String, MergerDescriptor>();
        private final Map<String, KeyProjectionDescriptor> keyProjections = new LinkedHashMap<String, KeyProjectionDescriptor>();
        private final Map<String, JoinDescriptor> joins = new LinkedHashMap<String, JoinDescriptor>();
        private final Map<String, ResumeDescriptor> resumePoints = new LinkedHashMap<String, ResumeDescriptor>();
        private final Map<TypeRef, TypeCodec<?>> typeCodecs = new LinkedHashMap<TypeRef, TypeCodec<?>>();
        private BindingResolver fallbackResolver = BindingResolver.defaultResolver();
        private PropertyAccessCompiler propertyAccessCompiler = DefaultPropertyAccessCompiler.INSTANCE;
        private final Map<String, TypeRef> typeAliases = new LinkedHashMap<String, TypeRef>();
        private TypeRef initialInputType = null;

        public Builder propertyAccessCompiler(PropertyAccessCompiler compiler) {
            this.propertyAccessCompiler = compiler != null ? compiler : DefaultPropertyAccessCompiler.INSTANCE;
            return this;
        }

        public Builder type(String alias, TypeRef typeRef) {
            this.typeAliases.put(Objects.requireNonNull(alias, "alias must not be null"),
                    Objects.requireNonNull(typeRef, "typeRef must not be null"));
            return this;
        }

        public Builder initialInputType(TypeRef initialInputType) {
            this.initialInputType = initialInputType;
            return this;
        }

        public Builder fallbackResolver(OperationResolver fallbackResolver) {
            this.fallbackResolver = fallbackResolver;
            return this;
        }

        public Builder fallbackResolver(BindingResolver fallbackResolver) {
            this.fallbackResolver = fallbackResolver;
            return this;
        }

        public Builder subflow(FlowDefinition definition) {
            Objects.requireNonNull(definition, "flow definition must not be null");
            return subflow(definition.id(), definition);
        }

        public Builder subflow(String id, FlowDefinition definition) {
            Objects.requireNonNull(id, "flow id must not be null");
            Objects.requireNonNull(definition, "flow definition must not be null");
            if (this.subflows.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate subflow registration for id: " + id
                        + ". Use overrideSubflow(...) to explicitly overwrite.");
            }
            this.subflows.put(id, definition);
            return this;
        }

        public Builder overrideSubflow(FlowDefinition definition) {
            Objects.requireNonNull(definition, "flow definition must not be null");
            return overrideSubflow(definition.id(), definition);
        }

        public Builder overrideSubflow(String id, FlowDefinition definition) {
            Objects.requireNonNull(id, "flow id must not be null");
            Objects.requireNonNull(definition, "flow definition must not be null");
            this.subflows.put(id, definition);
            return this;
        }

        public Builder subflows(Map<String, FlowDefinition> subflows) {
            if (subflows != null) {
                for (Map.Entry<String, FlowDefinition> entry : subflows.entrySet()) {
                    subflow(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public Builder overrideSubflows(Map<String, FlowDefinition> subflows) {
            if (subflows != null) {
                for (Map.Entry<String, FlowDefinition> entry : subflows.entrySet()) {
                    overrideSubflow(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public Builder subflows(Collection<FlowDefinition> definitions) {
            if (definitions != null) {
                for (FlowDefinition def : definitions) {
                    subflow(def);
                }
            }
            return this;
        }

        public Builder overrideSubflows(Collection<FlowDefinition> definitions) {
            if (definitions != null) {
                for (FlowDefinition def : definitions) {
                    overrideSubflow(def);
                }
            }
            return this;
        }

        public Builder operations(Map<String, OperationDescriptor> operations) {
            if (operations != null) {
                this.operations.putAll(operations);
            }
            return this;
        }

        public Builder policies(Map<String, PolicyDescriptor> policies) {
            if (policies != null) {
                this.policies.putAll(policies);
            }
            return this;
        }

        public Builder policyProviders(Map<String, PolicyProvider> policyProviders) {
            if (policyProviders != null) {
                this.policyProviders.putAll(policyProviders);
            }
            return this;
        }

        public Builder projectors(Map<String, ProjectorDescriptor> projectors) {
            if (projectors != null) {
                this.projectors.putAll(projectors);
            }
            return this;
        }

        public Builder mergers(Map<String, MergerDescriptor> mergers) {
            if (mergers != null) {
                this.mergers.putAll(mergers);
            }
            return this;
        }

        public Builder keyProjections(Map<String, KeyProjectionDescriptor> keyProjections) {
            if (keyProjections != null) {
                this.keyProjections.putAll(keyProjections);
            }
            return this;
        }

        public Builder joins(Map<String, JoinDescriptor> joins) {
            if (joins != null) {
                this.joins.putAll(joins);
            }
            return this;
        }

        public Builder resumePoints(Map<String, ResumeDescriptor> resumePoints) {
            if (resumePoints != null) {
                this.resumePoints.putAll(resumePoints);
            }
            return this;
        }

        public Builder typeCodecs(Map<TypeRef, TypeCodec<?>> typeCodecs) {
            if (typeCodecs != null) {
                this.typeCodecs.putAll(typeCodecs);
            }
            return this;
        }

        public Builder operation(OperationDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "operation descriptor must not be null");
            if (this.operations.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate operation registration for id: " + descriptor.id()
                        + ". Use overrideOperation(...) to explicitly overwrite.");
            }
            this.operations.put(descriptor.id(), descriptor);
            return this;
        }

        public Builder overrideOperation(OperationDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "operation descriptor must not be null");
            this.operations.put(descriptor.id(), descriptor);
            return this;
        }

        public <I, O> Builder operation(String id, Operation<I, O> instance, Class<I> input, Class<O> output) {
            return operation(OperationDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .inputType(TypeRef.of(input))
                    .outputType(TypeRef.of(output))
                    .build());
        }

        public <I, O> Builder overrideOperation(String id, Operation<I, O> instance, Class<I> input, Class<O> output) {
            return overrideOperation(OperationDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .inputType(TypeRef.of(input))
                    .outputType(TypeRef.of(output))
                    .build());
        }

        public <I, O> Builder operation(String id, Operation<I, O> instance) {
            Objects.requireNonNull(instance, "operation instance must not be null");
            TypeRef[] types = GenericTypeResolver.resolveOperationTypes(instance.getClass());
            return operation(OperationDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .inputType(types[0])
                    .outputType(types[1])
                    .build());
        }

        public <I, O> Builder overrideOperation(String id, Operation<I, O> instance) {
            Objects.requireNonNull(instance, "operation instance must not be null");
            TypeRef[] types = GenericTypeResolver.resolveOperationTypes(instance.getClass());
            return overrideOperation(OperationDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .inputType(types[0])
                    .outputType(types[1])
                    .build());
        }

        public Builder operation(String id, Class<? extends Operation<?, ?>> contract) {
            return operation(id, contract, (String) null);
        }

        public Builder overrideOperation(String id, Class<? extends Operation<?, ?>> contract) {
            return overrideOperation(id, contract, (String) null);
        }

        public Builder operation(String id, Class<? extends Operation<?, ?>> contract, String qualifier) {
            Objects.requireNonNull(contract, "operation contract must not be null");
            TypeRef[] types = GenericTypeResolver.resolveOperationTypes(contract);
            return operation(OperationDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .inputType(types[0])
                    .outputType(types[1])
                    .build());
        }

        public Builder overrideOperation(String id, Class<? extends Operation<?, ?>> contract, String qualifier) {
            Objects.requireNonNull(contract, "operation contract must not be null");
            TypeRef[] types = GenericTypeResolver.resolveOperationTypes(contract);
            return overrideOperation(OperationDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .inputType(types[0])
                    .outputType(types[1])
                    .build());
        }

        public <I, O> Builder operation(
                String id,
                Class<? extends Operation<I, O>> contract,
                Class<I> input,
                Class<O> output) {
            return operation(id, contract, null, input, output);
        }

        public <I, O> Builder overrideOperation(
                String id,
                Class<? extends Operation<I, O>> contract,
                Class<I> input,
                Class<O> output) {
            return overrideOperation(id, contract, null, input, output);
        }

        public <I, O> Builder operation(
                String id,
                Class<? extends Operation<I, O>> contract,
                String qualifier,
                Class<I> input,
                Class<O> output) {
            return operation(OperationDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .inputType(TypeRef.of(input))
                    .outputType(TypeRef.of(output))
                    .build());
        }

        public <I, O> Builder overrideOperation(
                String id,
                Class<? extends Operation<I, O>> contract,
                String qualifier,
                Class<I> input,
                Class<O> output) {
            return overrideOperation(OperationDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .inputType(TypeRef.of(input))
                    .outputType(TypeRef.of(output))
                    .build());
        }

        public Builder policy(PolicyDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "policy descriptor must not be null");
            if (this.policies.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate policy registration for id: " + descriptor.id()
                        + ". Use overridePolicy(...) to explicitly overwrite.");
            }
            this.policies.put(descriptor.id(), descriptor);
            return this;
        }

        public Builder overridePolicy(PolicyDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "policy descriptor must not be null");
            this.policies.put(descriptor.id(), descriptor);
            return this;
        }

        public <K> Builder policy(String id, Policy<K> instance, Class<K> keyType) {
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(TypeRef.of(keyType))
                    .persistent(false)
                    .build());
        }

        public <K> Builder overridePolicy(String id, Policy<K> instance, Class<K> keyType) {
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(TypeRef.of(keyType))
                    .persistent(false)
                    .build());
        }

        public <K> Builder policy(String id, Policy<K> instance) {
            Objects.requireNonNull(instance, "policy instance must not be null");
            TypeRef keyType = GenericTypeResolver.resolvePolicyKeyType(instance.getClass());
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(keyType)
                    .persistent(false)
                    .build());
        }

        public <K> Builder overridePolicy(String id, Policy<K> instance) {
            Objects.requireNonNull(instance, "policy instance must not be null");
            TypeRef keyType = GenericTypeResolver.resolvePolicyKeyType(instance.getClass());
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(keyType)
                    .persistent(false)
                    .build());
        }

        public Builder policy(String id, Class<? extends Policy<?>> contract) {
            return policy(id, contract, (String) null);
        }

        public Builder overridePolicy(String id, Class<? extends Policy<?>> contract) {
            return overridePolicy(id, contract, (String) null);
        }

        public Builder policy(String id, Class<? extends Policy<?>> contract, String qualifier) {
            Objects.requireNonNull(contract, "policy contract must not be null");
            TypeRef keyType = GenericTypeResolver.resolvePolicyKeyType(contract);
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(keyType)
                    .persistent(false)
                    .build());
        }

        public Builder overridePolicy(String id, Class<? extends Policy<?>> contract, String qualifier) {
            Objects.requireNonNull(contract, "policy contract must not be null");
            TypeRef keyType = GenericTypeResolver.resolvePolicyKeyType(contract);
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(keyType)
                    .persistent(false)
                    .build());
        }

        public <K> Builder policy(String id, Class<? extends Policy<K>> contract, Class<K> keyType) {
            return policy(id, contract, null, keyType);
        }

        public <K> Builder overridePolicy(String id, Class<? extends Policy<K>> contract, Class<K> keyType) {
            return overridePolicy(id, contract, null, keyType);
        }

        public <K> Builder policy(
                String id,
                Class<? extends Policy<K>> contract,
                String qualifier,
                Class<K> keyType) {
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(TypeRef.of(keyType))
                    .persistent(false)
                    .build());
        }

        public <K> Builder overridePolicy(
                String id,
                Class<? extends Policy<K>> contract,
                String qualifier,
                Class<K> keyType) {
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(TypeRef.of(keyType))
                    .persistent(false)
                    .build());
        }

        public <K, S> Builder persistentPolicy(String id, PersistentPolicy<K, S> instance, Class<K> keyType) {
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(TypeRef.of(keyType))
                    .persistent(true)
                    .build());
        }

        public <K, S> Builder overridePersistentPolicy(String id, PersistentPolicy<K, S> instance, Class<K> keyType) {
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(TypeRef.of(keyType))
                    .persistent(true)
                    .build());
        }

        public <K, S> Builder persistentPolicy(String id, PersistentPolicy<K, S> instance) {
            Objects.requireNonNull(instance, "persistent policy instance must not be null");
            TypeRef[] types = GenericTypeResolver.resolvePersistentPolicyTypes(instance.getClass());
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(types[0])
                    .persistent(true)
                    .build());
        }

        public <K, S> Builder overridePersistentPolicy(String id, PersistentPolicy<K, S> instance) {
            Objects.requireNonNull(instance, "persistent policy instance must not be null");
            TypeRef[] types = GenericTypeResolver.resolvePersistentPolicyTypes(instance.getClass());
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(types[0])
                    .persistent(true)
                    .build());
        }

        public Builder persistentPolicy(String id, Class<? extends PersistentPolicy<?, ?>> contract) {
            return persistentPolicy(id, contract, (String) null);
        }

        public Builder overridePersistentPolicy(String id, Class<? extends PersistentPolicy<?, ?>> contract) {
            return overridePersistentPolicy(id, contract, (String) null);
        }

        public Builder persistentPolicy(
                String id,
                Class<? extends PersistentPolicy<?, ?>> contract,
                String qualifier) {
            Objects.requireNonNull(contract, "persistent policy contract must not be null");
            TypeRef[] types = GenericTypeResolver.resolvePersistentPolicyTypes(contract);
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(types[0])
                    .persistent(true)
                    .build());
        }

        public Builder overridePersistentPolicy(
                String id,
                Class<? extends PersistentPolicy<?, ?>> contract,
                String qualifier) {
            Objects.requireNonNull(contract, "persistent policy contract must not be null");
            TypeRef[] types = GenericTypeResolver.resolvePersistentPolicyTypes(contract);
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(types[0])
                    .persistent(true)
                    .build());
        }

        public <K, S> Builder persistentPolicy(
                String id,
                Class<? extends PersistentPolicy<K, S>> contract,
                Class<K> keyType) {
            return persistentPolicy(id, contract, null, keyType);
        }

        public <K, S> Builder overridePersistentPolicy(
                String id,
                Class<? extends PersistentPolicy<K, S>> contract,
                Class<K> keyType) {
            return overridePersistentPolicy(id, contract, null, keyType);
        }

        public <K, S> Builder persistentPolicy(
                String id,
                Class<? extends PersistentPolicy<K, S>> contract,
                String qualifier,
                Class<K> keyType) {
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(TypeRef.of(keyType))
                    .persistent(true)
                    .build());
        }

        public <K, S> Builder overridePersistentPolicy(
                String id,
                Class<? extends PersistentPolicy<K, S>> contract,
                String qualifier,
                Class<K> keyType) {
            return overridePolicy(PolicyDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .keyType(TypeRef.of(keyType))
                    .persistent(true)
                    .build());
        }

        public Builder policyProvider(PolicyProvider provider) {
            Objects.requireNonNull(provider, "policy provider must not be null");
            String id = provider.descriptor().id();
            if (this.policyProviders.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate policy provider registration for id: " + id
                        + ". Use overridePolicyProvider(...) to explicitly overwrite.");
            }
            this.policyProviders.put(id, provider);
            return this;
        }

        public Builder overridePolicyProvider(PolicyProvider provider) {
            Objects.requireNonNull(provider, "policy provider must not be null");
            this.policyProviders.put(provider.descriptor().id(), provider);
            return this;
        }

        public Builder projector(ProjectorDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "projector descriptor must not be null");
            if (this.projectors.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate projector registration for id: " + descriptor.id()
                        + ". Use overrideProjector(...) to explicitly overwrite.");
            }
            this.projectors.put(descriptor.id(), descriptor);
            return this;
        }

        public Builder overrideProjector(ProjectorDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "projector descriptor must not be null");
            this.projectors.put(descriptor.id(), descriptor);
            return this;
        }

        public <I, P> Builder projector(String id, Class<I> input, Class<P> projected, Function<I, P> function) {
            return projector(ProjectorDescriptor.builder()
                    .id(id)
                    .inputType(TypeRef.of(input))
                    .outputType(TypeRef.of(projected))
                    .function(function)
                    .build());
        }

        public <I, P> Builder overrideProjector(String id, Class<I> input, Class<P> projected, Function<I, P> function) {
            return overrideProjector(ProjectorDescriptor.builder()
                    .id(id)
                    .inputType(TypeRef.of(input))
                    .outputType(TypeRef.of(projected))
                    .function(function)
                    .build());
        }

        public Builder merger(MergerDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "merger descriptor must not be null");
            if (this.mergers.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate merger registration for id: " + descriptor.id()
                        + ". Use overrideMerger(...) to explicitly overwrite.");
            }
            this.mergers.put(descriptor.id(), descriptor);
            return this;
        }

        public Builder overrideMerger(MergerDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "merger descriptor must not be null");
            this.mergers.put(descriptor.id(), descriptor);
            return this;
        }

        public <I, R, O> Builder merger(
                String id,
                Class<I> stateType,
                Class<R> resultType,
                Class<O> outputType,
                BiFunction<I, R, O> function) {
            return merger(MergerDescriptor.builder()
                    .id(id)
                    .stateType(TypeRef.of(stateType))
                    .resultType(TypeRef.of(resultType))
                    .outputType(TypeRef.of(outputType))
                    .function(function)
                    .build());
        }

        public <I, R, O> Builder overrideMerger(
                String id,
                Class<I> stateType,
                Class<R> resultType,
                Class<O> outputType,
                BiFunction<I, R, O> function) {
            return overrideMerger(MergerDescriptor.builder()
                    .id(id)
                    .stateType(TypeRef.of(stateType))
                    .resultType(TypeRef.of(resultType))
                    .outputType(TypeRef.of(outputType))
                    .function(function)
                    .build());
        }

        public Builder keyProjection(KeyProjectionDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "key projection descriptor must not be null");
            if (this.keyProjections.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate key projection registration for id: " + descriptor.id()
                        + ". Use overrideKeyProjection(...) to explicitly overwrite.");
            }
            this.keyProjections.put(descriptor.id(), descriptor);
            return this;
        }

        public Builder overrideKeyProjection(KeyProjectionDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "key projection descriptor must not be null");
            this.keyProjections.put(descriptor.id(), descriptor);
            return this;
        }

        public <I, K> Builder keyProjection(String id, Class<I> input, Class<K> keyType, Function<I, K> function) {
            return keyProjection(KeyProjectionDescriptor.builder()
                    .id(id)
                    .inputType(TypeRef.of(input))
                    .keyType(TypeRef.of(keyType))
                    .function(function)
                    .build());
        }

        public <I, K> Builder overrideKeyProjection(String id, Class<I> input, Class<K> keyType, Function<I, K> function) {
            return overrideKeyProjection(KeyProjectionDescriptor.builder()
                    .id(id)
                    .inputType(TypeRef.of(input))
                    .keyType(TypeRef.of(keyType))
                    .function(function)
                    .build());
        }

        public Builder join(JoinDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "join descriptor must not be null");
            if (this.joins.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate join registration for id: " + descriptor.id()
                        + ". Use overrideJoin(...) to explicitly overwrite.");
            }
            this.joins.put(descriptor.id(), descriptor);
            return this;
        }

        public Builder overrideJoin(JoinDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "join descriptor must not be null");
            this.joins.put(descriptor.id(), descriptor);
            return this;
        }

        public <I, O> Builder joinContextual(String id, Class<? extends ContextualJoinStrategy<I, O>> contract, Class<I> contextInputType, Class<O> outputType) {
            return joinContextual(id, contract, null, contextInputType, outputType);
        }

        public <I, O> Builder joinContextual(String id, Class<? extends ContextualJoinStrategy<I, O>> contract, String qualifier, Class<I> contextInputType, Class<O> outputType) {
            return join(JoinDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .contextInputType(TypeRef.of(contextInputType))
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public <I, O> Builder overrideJoinContextual(String id, Class<? extends ContextualJoinStrategy<I, O>> contract, Class<I> contextInputType, Class<O> outputType) {
            return overrideJoinContextual(id, contract, null, contextInputType, outputType);
        }

        public <I, O> Builder overrideJoinContextual(String id, Class<? extends ContextualJoinStrategy<I, O>> contract, String qualifier, Class<I> contextInputType, Class<O> outputType) {
            return overrideJoin(JoinDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .contextInputType(TypeRef.of(contextInputType))
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public <O> Builder join(String id, JoinStrategy<O> strategy, Class<O> outputType) {
            return join(JoinDescriptor.builder()
                    .id(id)
                    .strategy(strategy)
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public <O> Builder overrideJoin(String id, JoinStrategy<O> strategy, Class<O> outputType) {
            return overrideJoin(JoinDescriptor.builder()
                    .id(id)
                    .strategy(strategy)
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public <O> Builder join(String id, Class<? extends JoinStrategy<O>> contract, Class<O> outputType) {
            return join(id, contract, null, outputType);
        }

        public <O> Builder overrideJoin(String id, Class<? extends JoinStrategy<O>> contract, Class<O> outputType) {
            return overrideJoin(id, contract, null, outputType);
        }

        public <O> Builder join(String id, Class<? extends JoinStrategy<O>> contract, String qualifier, Class<O> outputType) {
            return join(JoinDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public <O> Builder overrideJoin(String id, Class<? extends JoinStrategy<O>> contract, String qualifier, Class<O> outputType) {
            return overrideJoin(JoinDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .qualifier(qualifier)
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public Builder resumePoint(ResumeDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "resume descriptor must not be null");
            if (this.resumePoints.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("Duplicate resume point registration for id: " + descriptor.id()
                        + ". Use overrideResumePoint(...) to explicitly overwrite.");
            }
            this.resumePoints.put(descriptor.id(), descriptor);
            return this;
        }

        public Builder overrideResumePoint(ResumeDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "resume descriptor must not be null");
            this.resumePoints.put(descriptor.id(), descriptor);
            return this;
        }

        public <S> Builder resumePoint(String id, Class<S> signalType) {
            return resumePoint(ResumeDescriptor.builder()
                    .id(id)
                    .signalType(TypeRef.of(signalType))
                    .build());
        }

        public <S> Builder overrideResumePoint(String id, Class<S> signalType) {
            return overrideResumePoint(ResumeDescriptor.builder()
                    .id(id)
                    .signalType(TypeRef.of(signalType))
                    .build());
        }

        public <T> Builder typeCodec(Class<T> type, TypeCodec<T> codec) {
            return typeCodec(TypeRef.of(type), codec);
        }

        public <T> Builder overrideTypeCodec(Class<T> type, TypeCodec<T> codec) {
            return overrideTypeCodec(TypeRef.of(type), codec);
        }

        public Builder typeCodec(TypeRef type, TypeCodec<?> codec) {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(codec, "codec must not be null");
            if (this.typeCodecs.containsKey(type)) {
                throw new IllegalArgumentException("Duplicate type codec registration for type: " + type.typeName()
                        + ". Use overrideTypeCodec(...) to explicitly overwrite.");
            }
            this.typeCodecs.put(type, codec);
            return this;
        }

        public Builder overrideTypeCodec(TypeRef type, TypeCodec<?> codec) {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(codec, "codec must not be null");
            this.typeCodecs.put(type, codec);
            return this;
        }

        public Builder apply(FlowDefinitionExtension extension) {
            Objects.requireNonNull(extension, "extension must not be null");
            extension.contribute(this);
            return this;
        }

        public FlowDefinitionRegistry build() {
            return new FlowDefinitionRegistry(this);
        }
    }
}
