package com.team4u.framework.flow.durable.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 复合链式业务数据编解码器（Composite State Mapper）。
 *
 * <p>按顺序尝试多个 {@link StateMapper} 进行编解码。编码时优先尝试前序映射器（如 {@link DefaultStateMapper} 标量编码），
 * 若前序映射器无法处理则回退到后续映射器（如基于 Jackson/JSON 的对象序列化映射器）。</p>
 *
 * @author jay.wu
 */
public final class CompositeStateMapper implements StateMapper {
    private final List<StateMapper> mappers;

    public CompositeStateMapper(List<StateMapper> mappers) {
        Objects.requireNonNull(mappers, "mappers must not be null");
        if (mappers.isEmpty()) {
            throw new IllegalArgumentException("mappers must not be empty");
        }
        this.mappers = Collections.unmodifiableList(new ArrayList<StateMapper>(mappers));
    }

    /**
     * 便捷构建以 {@link DefaultStateMapper} 为基础、指定 mapper 为兜底扩展的复合映射器。
     */
    public static CompositeStateMapper withDefault(StateMapper fallbackMapper) {
        Objects.requireNonNull(fallbackMapper, "fallbackMapper must not be null");
        List<StateMapper> list = new ArrayList<StateMapper>(2);
        list.add(DefaultStateMapper.INSTANCE);
        list.add(fallbackMapper);
        return new CompositeStateMapper(list);
    }

    @Override
    public StoredValue encode(Object value) throws Exception {
        Objects.requireNonNull(value, "value must not be null");
        Exception lastException = null;
        for (StateMapper mapper : mappers) {
            try {
                return mapper.encode(value);
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new IllegalArgumentException("No StateMapper could encode value of type: "
                + value.getClass().getName(), lastException);
    }

    @Override
    public Object decode(StoredValue storedValue) throws Exception {
        Objects.requireNonNull(storedValue, "storedValue must not be null");
        Exception lastException = null;
        for (StateMapper mapper : mappers) {
            try {
                return mapper.decode(storedValue);
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new IllegalArgumentException("No StateMapper could decode StoredValue with codecId: "
                + storedValue.codecId(), lastException);
    }
}
