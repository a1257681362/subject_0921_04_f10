package com.evops.telematics.xml;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDateTime;

/**
 * XML 异常清单：来报解析/校验失败、导出组报文失败、回归验证往返不一致，
 * 统一进清单待人工处置。核销必须填原因（与对账差异核销同口径）。
 */
@TableName("t_telematics_xml_exception")
public class XmlExceptionRecord extends BizEntity {

    /** 平台来报解析/校验失败 */
    public static final String SOURCE_INBOUND = "INBOUND";
    /** 我方导出组报文失败（脏数据等） */
    public static final String SOURCE_EXPORT = "EXPORT";
    /** 上线前回归验证往返不一致 */
    public static final String SOURCE_REGRESSION = "REGRESSION";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** INBOUND / EXPORT / REGRESSION */
    private String source;

    /** IN / OUT */
    private String direction;

    /** 报文类型（能识别则填） */
    private String msgType;

    /** 关联订单号（能识别则填） */
    private String orderNo;

    /** 回归批次 id（source=REGRESSION 时关联） */
    private Long runId;

    /** 明确错误：元素/属性路径 + 原因，逐条以换行分隔 */
    private String errorDetail;

    /** 原始报文/内容（排障依据） */
    private String rawContent;

    /** OPEN 待处置 / RESOLVED 已核销 */
    private String status = STATUS_OPEN;

    /** 核销原因（必填） */
    private String resolveReason;

    private String resolveOperator;

    private LocalDateTime resolveTime;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getRawContent() { return rawContent; }
    public void setRawContent(String rawContent) { this.rawContent = rawContent; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveReason() { return resolveReason; }
    public void setResolveReason(String resolveReason) { this.resolveReason = resolveReason; }
    public String getResolveOperator() { return resolveOperator; }
    public void setResolveOperator(String resolveOperator) { this.resolveOperator = resolveOperator; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
}
