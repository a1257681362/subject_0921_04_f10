package com.evops.telematics.xml;

/**
 * XML 报文格式错误：XML 语法错误（带行列号）、根元素不符、含 XML 1.0 非法字符、
 * 元素结构不符（叶子元素带子元素）等。错误信息面向对接双方可直接定位。
 */
public class XmlFormatException extends RuntimeException {

    public XmlFormatException(String message) {
        super(message);
    }

    public XmlFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
