package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;
import java.io.Serializable;

/**
 * 投影规范抽象接口（Projection Spec）。
 *
 * @author jay.wu
 */
public interface ProjectionSpec extends Serializable {

    /**
     * 获取源码位置。
     *
     * @return 源码位置信息
     */
    SourceSpan span();
}
