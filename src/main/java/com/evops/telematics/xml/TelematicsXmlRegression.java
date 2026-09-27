package com.evops.telematics.xml;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDate;

/**
 * 上线前回归验证批次：用网点上一期的租车单走一轮“组报文 -> XML -> 解析回对象”
 * 往返比对。逐单留结果，失败明细进异常清单（挂 run_id），本表记批次汇总。
 */
@TableName("t_telematics_xml_regression")
public class TelematicsXmlRegression extends BizEntity {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    /** 验证范围：归属网点 */
    private String branchCode;

    /** 验证范围：租车单业务时间起（含） */
    private LocalDate periodFrom;

    /** 验证范围：租车单业务时间止（含） */
    private LocalDate periodTo;

    /** 覆盖租车单数 */
    private Integer totalOrders = 0;

    /** 生成报文数（一交接一条） */
    private Integer totalMessages = 0;

    /** 往返比对通过数 */
    private Integer passedCount = 0;

    /** 失败数（明细见异常清单 run_id） */
    private Integer failedCount = 0;

    /** NULL 进行中 / SUCCESS 全部通过 / FAILED 存在失败（与 recon 作业运行记录同口径） */
    private String status;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(LocalDate periodFrom) { this.periodFrom = periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public void setPeriodTo(LocalDate periodTo) { this.periodTo = periodTo; }
    public Integer getTotalOrders() { return totalOrders; }
    public void setTotalOrders(Integer totalOrders) { this.totalOrders = totalOrders; }
    public Integer getTotalMessages() { return totalMessages; }
    public void setTotalMessages(Integer totalMessages) { this.totalMessages = totalMessages; }
    public Integer getPassedCount() { return passedCount; }
    public void setPassedCount(Integer passedCount) { this.passedCount = passedCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
