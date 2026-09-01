package com.team4u.framework.flow.definition.registry;

import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.definition.type.ClassTypeRef;
import com.team4u.framework.flow.definition.type.TypeCodec;
import com.team4u.framework.flow.definition.type.TypeCodecs;
import com.team4u.framework.flow.definition.type.TypeRef;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 流程定义外部符号注册表（Flow Definition Registry）。
 *
 * <p>统一管理流程 DSL 中引用的所有 Operation、Policy、Projector、Merger、KeyProjection、Join、
 * ResumePoint 及 TypeCodec 映射关系，实现 DSL 符号与 Java 类/Bean 的解耦。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class FlowDefinitionRegistry {

    private final Map<String, OperationDescriptor> operations;
    private final Map<String, PolicyDescriptor> policies;
    private final Map<String, PolicyProvider> policyProviders;
    private final Map<String, ProjectorDescriptor> projectors;
    private final Map<String, MergerDescriptor> mergers;
    private final Map<String, KeyProjectionDescriptor> keyProjections;
    private final Map<String, JoinDescriptor> joins;
    private final Map<String, ResumeDescriptor> resumePoints;
    private final Map<TypeRef, TypeCodec<?>> typeCodecs;

    private FlowDefinitionRegistry(Builder builder) {
        this.operations = Collections.unmodifiableMap(new LinkedHashMap<String, OperationDescriptor>(builder.operations));
        this.policies = Collections.unmodifiableMap(new LinkedHashMap<String, PolicyDescriptor>(builder.policies));
        this.policyProviders = Collections.unmodifiableMap(new LinkedHashMap<String, PolicyProvider>(builder.policyProviders));
        this.projectors = Collections.unmodifiableMap(new LinkedHashMap<String, ProjectorDescriptor>(builder.projectors));
        this.mergers = Collections.unmodifiableMap(new LinkedHashMap<String, MergerDescriptor>(builder.mergers));
        this.keyProjections = Collections.unmodifiableMap(new LinkedHashMap<String, KeyProjectionDescriptor>(builder.keyProjections));
        this.joins = Collections.unmodifiableMap(new LinkedHashMap<String, JoinDescriptor>(builder.joins));
        this.resumePoints = Collections.unmodifiableMap(new LinkedHashMap<String, ResumeDescriptor>(builder.resumePoints));
        this.typeCodecs = Collections.unmodifiableMap(new LinkedHashMap<TypeRef, TypeCodec<?>>(builder.typeCodecs));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FlowDefinitionRegistry empty() {
        return builder().build();
    }

    public OperationDescriptor operation(String id) {
        return operations.get(id);
    }

    public PolicyDescriptor policy(String id) {
        PolicyDescriptor descriptor = policies.get(id);
        if (descriptor != null) {
            return descriptor;
        }
        PolicyProvider provider = policyProviders.get(id);
        if (provider != null) {
            return provider.descriptor();
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
        private final Map<String, OperationDescriptor> operations = new LinkedHashMap<String, OperationDescriptor>();
        private final Map<String, PolicyDescriptor> policies = new LinkedHashMap<String, PolicyDescriptor>();
        private final Map<String, PolicyProvider> policyProviders = new LinkedHashMap<String, PolicyProvider>();
        private final Map<String, ProjectorDescriptor> projectors = new LinkedHashMap<String, ProjectorDescriptor>();
        private final Map<String, MergerDescriptor> mergers = new LinkedHashMap<String, MergerDescriptor>();
        private final Map<String, KeyProjectionDescriptor> keyProjections = new LinkedHashMap<String, KeyProjectionDescriptor>();
        private final Map<String, JoinDescriptor> joins = new LinkedHashMap<String, JoinDescriptor>();
        private final Map<String, ResumeDescriptor> resumePoints = new LinkedHashMap<String, ResumeDescriptor>();
        private final Map<TypeRef, TypeCodec<?>> typeCodecs = new LinkedHashMap<TypeRef, TypeCodec<?>>();

        public Builder operation(OperationDescriptor descriptor) {
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

        public <I, O> Builder operation(String id, Operation<I, O> instance) {
            return operation(OperationDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .build());
        }

        public <I, O> Builder operation(
                String id,
                Class<? extends Operation<I, O>> contract,
                Class<I> input,
                Class<O> output) {
            return operation(id, contract, null, input, output);
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

        public Builder policy(PolicyDescriptor descriptor) {
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

        public <K> Builder policy(String id, Policy<K> instance) {
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .persistent(false)
                    .build());
        }

        public <K> Builder policy(String id, Class<? extends Policy<K>> contract, Class<K> keyType) {
            return policy(id, contract, null, keyType);
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

        public <K, S> Builder persistentPolicy(String id, PersistentPolicy<K, S> instance, Class<K> keyType) {
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .keyType(TypeRef.of(keyType))
                    .persistent(true)
                    .build());
        }

        public <K, S> Builder persistentPolicy(String id, PersistentPolicy<K, S> instance) {
            return policy(PolicyDescriptor.builder()
                    .id(id)
                    .instance(instance)
                    .persistent(true)
                    .build());
        }

        public <K, S> Builder persistentPolicy(
                String id,
                Class<? extends PersistentPolicy<K, S>> contract,
                Class<K> keyType) {
            return persistentPolicy(id, contract, null, keyType);
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

        public Builder policyProvider(PolicyProvider provider) {
            Objects.requireNonNull(provider, "policy provider must not be null");
            this.policyProviders.put(provider.descriptor().id(), provider);
            return this;
        }

        public Builder projector(ProjectorDescriptor descriptor) {
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

        public Builder merger(MergerDescriptor descriptor) {
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

        public Builder keyProjection(KeyProjectionDescriptor descriptor) {
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

        public Builder join(JoinDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "join descriptor must not be null");
            this.joins.put(descriptor.id(), descriptor);
            return this;
        }

        public <O> Builder join(String id, JoinStrategy<O> strategy, Class<O> outputType) {
            return join(JoinDescriptor.builder()
                    .id(id)
                    .strategy(strategy)
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public <O> Builder join(String id, Class<? extends JoinStrategy<O>> contract, Class<O> outputType) {
            return join(JoinDescriptor.builder()
                    .id(id)
                    .contract(contract)
                    .outputType(TypeRef.of(outputType))
                    .build());
        }

        public Builder resumePoint(ResumeDescriptor descriptor) {
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

        public <T> Builder typeCodec(Class<T> type, TypeCodec<T> codec) {
            return typeCodec(TypeRef.of(type), codec);
        }

        public Builder typeCodec(TypeRef type, TypeCodec<?> codec) {
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
