package com.team4u.framework.serializer.json.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.serializer.json.JsonSerializerPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 基于 Jackson 实现的 JSON 序列化策略
 * <p>
 * 本类是全局共享 {@link ObjectMapper} 的唯一权威来源：
 * 所有走 {@link com.team4u.framework.serializer.json.JsonUtil} 的 JSON 序列化
 * 都经由这里维护的共享 mapper 执行，各业务模块不得私建 ObjectMapper。
 * <p>
 * <b>模块注册扩展点</b>：共享 mapper 支持两种方式注册扩展模块——
 * <ul>
 *   <li>静态注册：调用 {@link #registerModule(Module)}。建议在应用启动阶段
 *       （首次 JSON 访问前）调用；若共享 mapper 已经初始化并投入使用，
 *       注册同样立即生效（内部基于「基础配置 + 全量模块」重建 mapper，
 *       刷新 Jackson 已缓存的序列化器，避免晚注册的模块对已序列化类型不生效）。</li>
 *   <li>SPI 注册：实现 {@link JacksonModuleContributor} 并提供服务文件，
 *       共享 mapper 首次初始化时通过 {@link ServiceLoader} 自动发现并注册。</li>
 * </ul>
 * 重复注册同一模块（按「模块实现类 + 模块名」判定）是幂等的，
 * 不会产生序列化器/修饰器叠加等副作用。
 *
 * @author jay.wu
 */
public class JacksonSerializerPolicy implements JsonSerializerPolicy {

    /**
     * 共享 ObjectMapper 的惰性持有者
     * <p>
     * 首次被引用时完成初始化：应用基础配置、收集 SPI 贡献的模块。
     * 之后模块集发生变化时通过 {@link #build()} 整体重建并原子替换。
     */
    private static class SharedMapperHolder {

        private static final Logger LOG = LoggerFactory.getLogger(SharedMapperHolder.class);

        /**
         * 已注册的扩展模块（键为「模块实现类全名 + 模块名」，值为模块实例，保持注册顺序）
         */
        private static final Map<String, Module> REGISTERED_MODULES =
                Collections.synchronizedMap(new LinkedHashMap<String, Module>());

        /**
         * 共享 mapper。volatile 保证重建后的替换对所有读线程可见。
         */
        private static volatile ObjectMapper MAPPER = build();

        /**
         * 基于「基础配置 + 当前全量已注册模块」构建一个新的共享 mapper
         * <p>
         * 每次模块集变化都整体重建，而不是对既有 mapper 增量 registerModule：
         * Jackson 对已序列化过的类型会缓存序列化器，增量注册无法覆盖这些缓存，
         * 会导致晚注册的模块（如脱敏）对早期已缓存的类型不生效。
         */
        private static ObjectMapper build() {
            ObjectMapper mapper = new ObjectMapper();
            // 注册 Java8 时间模块
            mapper.registerModule(new JavaTimeModule());
            // 忽略未知的属性
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            // 不包含 null 值
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            // 格式化日期时不转为时间戳
            mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

            // 注册 SPI 贡献的模块（单个 contributor 失败只影响其自身模块，
            // 不影响其他模块与基础序列化能力）
            collectFromServiceLoader();

            // 注册静态 API / SPI 收集到的全部模块
            for (Module module : snapshotModules()) {
                mapper.registerModule(module);
            }
            return mapper;
        }

        /**
         * 通过 ServiceLoader 收集 {@link JacksonModuleContributor} 贡献的模块
         */
        private static void collectFromServiceLoader() {
            ServiceLoader<JacksonModuleContributor> loader =
                    ServiceLoader.load(JacksonModuleContributor.class);
            Iterator<JacksonModuleContributor> it = loader.iterator();
            while (true) {
                try {
                    if (!it.hasNext()) {
                        return;
                    }
                } catch (Throwable t) {
                    LOG.warn("JacksonSerializerPolicy|loadModuleContributor|fail|msg={}", t.getMessage());
                    return;
                }
                JacksonModuleContributor contributor;
                try {
                    contributor = it.next();
                } catch (Throwable t) {
                    // 服务文件损坏等导致的 ServiceConfigurationError：跳过该条目
                    LOG.warn("JacksonSerializerPolicy|initModuleContributor|fail|msg={}", t.getMessage());
                    continue;
                }
                try {
                    recordModules(contributor.modules());
                } catch (Throwable t) {
                    LOG.warn("JacksonSerializerPolicy|registerContributedModules|fail|contributor={}|msg={}",
                            contributor.getClass().getName(), t.getMessage());
                }
            }
        }

        /**
         * 记录模块（去重、保序）；返回是否发生了有效新增
         */
        private static boolean recordModules(Collection<Module> modules) {
            if (modules == null) {
                return false;
            }
            boolean changed = false;
            synchronized (REGISTERED_MODULES) {
                for (Module module : modules) {
                    if (module == null || REGISTERED_MODULES.containsKey(moduleKey(module))) {
                        continue;
                    }
                    REGISTERED_MODULES.put(moduleKey(module), module);
                    changed = true;
                }
            }
            return changed;
        }

        private static List<Module> snapshotModules() {
            synchronized (REGISTERED_MODULES) {
                return new ArrayList<Module>(REGISTERED_MODULES.values());
            }
        }

        private static String moduleKey(Module module) {
            return module.getClass().getName() + "#" + module.getModuleName();
        }
    }

    /**
     * 向共享 ObjectMapper 注册扩展模块（如脱敏模块、日志截断模块）
     * <p>
     * 建议在应用启动阶段（首次 JSON 访问前）调用；若共享 mapper 已初始化并投入使用，
     * 本方法同样立即生效：内部会基于「基础配置 + 全量已注册模块」重建共享 mapper，
     * 刷新 Jackson 已缓存的序列化器。
     * <p>
     * 重复注册（按「模块实现类 + 模块名」判定）是幂等的。
     *
     * @param module Jackson 模块，不能为 null
     * @return 是否真正发生了注册（重复注册时返回 false）
     */
    public static boolean registerModule(Module module) {
        if (module == null) {
            throw new IllegalArgumentException("module must not be null");
        }
        return registerModules(Collections.singletonList(module));
    }

    /**
     * 批量向共享 ObjectMapper 注册扩展模块，语义同 {@link #registerModule(Module)}
     *
     * @param modules Jackson 模块集合，不能为 null（可包含 null 元素，将被忽略）
     * @return 是否真正发生了注册（全部为重复注册时返回 false）
     */
    public static boolean registerModules(Collection<Module> modules) {
        if (modules == null) {
            throw new IllegalArgumentException("modules must not be null");
        }
        // 触发 holder 初始化（若尚未初始化），保证时序语义：
        // holder 未初始化时模块先进记录表，随后由初始化构建统一注册；
        // 已初始化时记录后立即重建生效
        boolean changed = SharedMapperHolder.recordModules(modules);
        if (changed) {
            synchronized (SharedMapperHolder.class) {
                SharedMapperHolder.MAPPER = SharedMapperHolder.build();
            }
        }
        return changed;
    }

    /**
     * 获取全局共享的 ObjectMapper
     * <p>
     * <b>警告</b>：返回的实例全局共享，仅供读取与序列化操作
     * （如 {@code mapper.writer().withAttribute(...)}），
     * <b>严禁</b> 直接修改其配置（registerModule / configure / setXXX 等），
     * 那会影响所有使用方。注册扩展模块必须走 {@link #registerModule(Module)}。
     * <p>
     * 设计说明：不返回 {@code copy()}，因为副本不共享序列化器缓存，
     * 每次访问全量重建缓存开销大，且副本上的注册不会回写共享实例，违背「共享」初衷；
     * 也不做不可配置的包装代理，因为 ObjectMapper 大量方法返回 this 型链式 API，
     * 包装易出现「改了副本、误以为改了共享」的隐患。因此选择直接暴露 +
     * javadoc 约定警示，并在 {@link #registerModule(Module)} 提供唯一受控注册入口。
     * <p>
     * 注意：注册新模块会触发共享 mapper 整体重建，此前通过本方法拿到的旧引用
     * 仍可用（保留旧模块集），但不会自动感知新模块；需要最新模块集时应重新调用本方法。
     *
     * @return 共享的 ObjectMapper（不要修改它的配置）
     */
    public static ObjectMapper sharedMapper() {
        return SharedMapperHolder.MAPPER;
    }

    private static ObjectMapper mapper() {
        return SharedMapperHolder.MAPPER;
    }

    @Override
    public String toJsonStr(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return mapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert object to json string error", e);
        }
    }

    @Override
    public <T> T toBean(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapper().readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert json string to bean error", e);
        }
    }

    @Override
    public <T> T toBean(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JavaType javaType = mapper().getTypeFactory().constructType(type);
            return mapper().readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert json string to bean error", e);
        }
    }

    @Override
    public <T> List<T> toList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JavaType type = mapper().getTypeFactory().constructCollectionType(List.class, clazz);
            return mapper().readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert json string to list error", e);
        }
    }

    @Override
    public Object parseObj(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Parse json string error", e);
        }
    }

    @Override
    public boolean supports(Void context) {
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return ContextPolicy.HIGH;
    }

    @Override
    public String key() {
        return "jackson";
    }
}
