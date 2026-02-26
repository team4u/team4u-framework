package com.team4u.framework.criterion.parser.handler;

import org.junit.Assert;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.PropertyCriterion;

/**
 * 语法处理器测试基类
 * <p>
 * 提供通用的解析辅助工具和标准的规则解析器实例。
 */
public abstract class AbstractSyntaxHandlerTest {

    protected final Criteria criteria = Criteria.global();

    /**
     * 解析表达式并验证叶子节点的类型
     *
     * @param expression   表达式字符串
     * @param expectedType 期望的叶子节点类型
     * @param <T>          类型占位符
     * @return 解析出的叶子节点
     */
    @SuppressWarnings("unchecked")
    protected <T extends Criterion> T parseLeaf(String expression, Class<T> expectedType) {
        Criterion c = criteria.parse(expression);
        // 如果是属性规则，解包获取内部真实的规则节点
        if (c instanceof PropertyCriterion) {
            c = ((PropertyCriterion) c).getCriterion();
        }
        Assert.assertEquals("解析出的规则类型不匹配: " + expression, expectedType, c.getClass());
        return (T) c;
    }
}
