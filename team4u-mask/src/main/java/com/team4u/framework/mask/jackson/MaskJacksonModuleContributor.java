package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.databind.Module;
import com.team4u.framework.serializer.json.jackson.JacksonModuleContributor;

import java.util.Collection;
import java.util.Collections;

/**
 * 脱敏模块的 Jackson SPI 贡献者
 * <p>
 * 通过标准 ServiceLoader 机制被 {@code JacksonSerializerPolicy} 在共享
 * {@link com.fasterxml.jackson.databind.ObjectMapper} 首次初始化时自动发现，
 * 贡献 {@link JacksonMaskModule}，使任何依赖 team4u-mask 的应用在
 * {@code JsonUtil} 全局序列化时 {@code @Mask} 注解与脱敏规则自动生效，
 * 无需各模块私建 ObjectMapper。
 * <p>
 * 注意：脱敏模块对 {@code MaskRuleRepository} 的规则查询是运行时动态进行的，
 * 因此单例模块实例即可感知规则热更新。
 *
 * @author jay.wu
 */
public class MaskJacksonModuleContributor implements JacksonModuleContributor {

    /**
     * 单例模块（模块自身无状态，修饰器在运行期动态查询规则）
     */
    private static final Collection<Module> MODULES =
            Collections.singletonList(new JacksonMaskModule());

    @Override
    public Collection<Module> modules() {
        return MODULES;
    }
}
