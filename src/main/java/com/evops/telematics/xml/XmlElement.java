package com.evops.telematics.xml;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * XML 子元素映射（字段级注解）。字段值序列化为根元素下的子元素。
 *
 * 空值与缺省区分：null -> 元素整体缺省（绝不补 0/false/空串等默认值）；
 * 空串 -> 输出自闭合元素 {@code <x/>}。解析时元素缺省 -> null，
 * {@code <x/>} -> 空串（String 字段），二者往返互不串味。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface XmlElement {

    /** 元素名，如 orderNo */
    String name();

    /** 必填：序列化/解析时缺失即报明确错误 */
    boolean required() default false;

    /** 取值白名单（String 字段），不在列即校验错误并列出合法值；空数组表示不限制 */
    String[] allowed() default {};
}
