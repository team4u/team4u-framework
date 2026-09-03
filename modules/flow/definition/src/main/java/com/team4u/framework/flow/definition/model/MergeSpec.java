package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;
import java.io.Serializable;

/**
 * 结果合并规范抽象接口（Merge Spec）。
 *
 * @author jay.wu
 */
public interface MergeSpec extends Serializable {

    /**
     * 获取源码位置。
     *
     * @return 源码位置信息
     */
    SourceSpan span();
}
