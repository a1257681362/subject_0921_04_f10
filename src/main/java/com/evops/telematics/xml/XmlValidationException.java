package com.evops.telematics.xml;

import java.util.Collections;
import java.util.List;

/**
 * XML 报文字段校验错误：必填缺失 / 类型不符 / 取值越出白名单等。
 * 一次解析收集全部错误（非遇错即停），errors 逐条为“元素/属性 xxx：原因”。
 */
public class XmlValidationException extends RuntimeException {

    private final List<String> errors;

    public XmlValidationException(List<String> errors) {
        super(String.join("；", errors));
        this.errors = Collections.unmodifiableList(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
