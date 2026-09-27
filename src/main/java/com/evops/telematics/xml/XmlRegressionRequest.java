package com.evops.telematics.xml;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 回归验证请求：用该网点期间内（按租车单业务时间）的租车单走一轮往返比对。
 */
public class XmlRegressionRequest {

    /** 归属网点编码 */
    @NotBlank(message = "branchCode 不能为空")
    private String branchCode;

    /** 期间起（含），yyyy-MM-dd */
    @NotNull(message = "periodFrom 不能为空")
    private LocalDate periodFrom;

    /** 期间止（含），yyyy-MM-dd */
    @NotNull(message = "periodTo 不能为空")
    private LocalDate periodTo;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(LocalDate periodFrom) { this.periodFrom = periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public void setPeriodTo(LocalDate periodTo) { this.periodTo = periodTo; }
}
