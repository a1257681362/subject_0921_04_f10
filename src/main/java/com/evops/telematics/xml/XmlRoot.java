package com.evops.telematics.xml;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * XML 报文根元素映射（类级注解）。报文模型类必须标注，name 为根元素名。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface XmlRoot {

    /** 根元素名，如 handoverNotice */
    String name();
}
