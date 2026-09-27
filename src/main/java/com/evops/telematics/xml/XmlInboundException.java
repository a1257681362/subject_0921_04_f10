package com.evops.telematics.xml;

import java.util.Collections;
import java.util.List;

/**
 * 平台来报解析/校验未通过：异常清单已落库（exceptionId），本异常携带逐条明确错误
 * 返回给调用方（HTTP 400，code=XML_MESSAGE_INVALID）。
 */
public class XmlInboundException extends RuntimeException {

    private final List<String> errors;
    private final Long exceptionId;

    public XmlInboundException(List<String> errors, Long exceptionId) {
        super("来报校验未通过：" + String.join("；", errors));
        this.errors = Collections.unmodifiableList(errors);
        this.exceptionId = exceptionId;
    }

    public List<String> getErrors() {
        return errors;
    }

    public Long getExceptionId() {
        return exceptionId;
    }
}
