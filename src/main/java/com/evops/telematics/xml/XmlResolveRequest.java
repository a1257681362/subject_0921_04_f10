package com.evops.telematics.xml;

import javax.validation.constraints.NotBlank;

/**
 * 异常核销请求：原因必填（与对账差异核销同口径）。
 */
public class XmlResolveRequest {

    @NotBlank(message = "核销原因必填")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
