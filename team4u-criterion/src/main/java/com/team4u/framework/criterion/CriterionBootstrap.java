package com.team4u.framework.criterion;

import com.team4u.framework.criterion.compiler.CompilerRegistry;
import com.team4u.framework.criterion.compiler.CriterionCompiler;
import com.team4u.framework.criterion.model.convert.ValueConverter;
import com.team4u.framework.criterion.model.convert.ValueConverterRegistry;
import com.team4u.framework.criterion.parser.impl.StandardCriterionParser;

import java.util.function.BiPredicate;

/**
 * 表达式引擎全局引导配置类
 * <p>
 * 提供统一的入口进行全局算子、转换器及编译器的注册，避免注册逻辑散落在各处。
 * 支持锁定机制，确保应用启动后的稳定性。
 */
public class CriterionBootstrap {

    private static final CriterionBootstrap INSTANCE = new CriterionBootstrap();

    private volatile boolean locked = false;

    private CriterionBootstrap() {
    }

    /**
     * 获取全局引导实例
     */
    public static CriterionBootstrap global() {
        return INSTANCE;
    }

    /**
     * 注册全局自定义操作符
     *
     * @param operator 算子名称（如 "is_odd"）
     * @param logic    匹配逻辑 (actual, expected) -> boolean
     */
    public synchronized CriterionBootstrap registerOperator(String operator, BiPredicate<Object, Object> logic) {
        checkLocked();
        StandardCriterionParser.global().addOperator(operator, logic);
        return this;
    }

    /**
     * 注册全局值转换器
     *
     * @param converter 转换器实现
     */
    public synchronized CriterionBootstrap registerConverter(ValueConverter converter) {
        checkLocked();
        ValueConverterRegistry.global().register(converter);
        return this;
    }

    /**
     * 注册全局规则编译器
     *
     * @param compiler 编译器实现
     */
    public synchronized CriterionBootstrap registerCompiler(CriterionCompiler<?> compiler) {
        checkLocked();
        CompilerRegistry.global().register(compiler);
        return this;
    }

    /**
     * 锁定全局注册表
     * <p>
     * 调用后将禁止任何新的注册操作，建议在应用启动完成（如 Spring 启动成功）后调用。
     */
    public synchronized void lock() {
        this.locked = true;
    }

    /**
     * 检查是否已锁定
     */
    private void checkLocked() {
        if (locked) {
            throw new IllegalStateException("Criterion global registry is locked, no more registrations allowed.");
        }
    }
}
