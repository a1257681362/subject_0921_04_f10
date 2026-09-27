package com.evops.telematics.xml;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * XML 属性映射（字段级注解）。字段值序列化为根元素属性；
 * null 一律缺省（不输出属性、不补默认值），解析时属性缺失映射为 null。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface XmlAttribute {

    /** 属性名，如 msgId */
    String name();

    /** 必填：序列化/解析时缺失即报明确错误 */
    boolean required() default false;
}
