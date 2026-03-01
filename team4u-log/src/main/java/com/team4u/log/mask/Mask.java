package com.team4u.log.mask;

import java.lang.annotation.*;

/**
 * 脱敏注解
 * <p>
 * 标注在字段上，指定执行的脱敏逻辑。
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Mask {
    /**
     * 脱敏类型
     */
    MaskType value();
}
