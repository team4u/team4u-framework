package com.team4u.framework.serializer.json.jackson;

import com.fasterxml.jackson.databind.Module;

import java.util.Collection;

/**
 * Jackson 模块贡献者（SPI 扩展点）
 * <p>
 * 实现方通过标准的 {@link java.util.ServiceLoader} 机制被发现：
 * 在自己的 jar 中提供
 * {@code META-INF/services/com.team4u.framework.serializer.json.jackson.JacksonModuleContributor}
 * 服务文件（内容为实现类的全限定名），{@link JacksonSerializerPolicy} 在共享
 * {@link com.fasterxml.jackson.databind.ObjectMapper} 首次初始化时，
 * 会自动收集所有 contributor 贡献的 {@link Module} 并注册进共享 mapper。
 * <p>
 * 典型场景：脱敏模块（JacksonMaskModule）、日志截断模块等通过本接口
 * 无侵入地增强全局 JSON 序列化行为，避免各模块私建 ObjectMapper。
 * <p>
 * 服务文件由实现方提供，serializer-jackson 模块自身无需（也不应）注册本接口的实现。
 *
 * @author jay.wu
 */
public interface JacksonModuleContributor {

    /**
     * 贡献需要注册进共享 ObjectMapper 的 Jackson 模块
     *
     * @return 模块集合，可为空集合，但不能为 null
     */
    Collection<Module> modules();
}
