package com.evops.telematics.xml;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDateTime;

/**
 * XML 报文归档记录（收发双向）。报文按订单号分目录落盘归档，原文同时落库：
 * 重发走归档原报文（content 逐字节一致），不从当前业务数据重新生成。
 * (direction, msg_id) 唯一：平台同 msgId 回执重复接收幂等，直接返回首次结果。
 */
@TableName("t_telematics_xml_message")
public class TelematicsXmlMessage extends BizEntity {

    public static final String DIRECTION_OUT = "OUT";
    public static final String DIRECTION_IN = "IN";

    /** OUT：已生成归档 */
    public static final String STATUS_GENERATED = "GENERATED";
    /** IN：已接收归档 */
    public static final String STATUS_RECEIVED = "RECEIVED";

    /** 报文唯一标识（OUT 我方生成 / IN 平台报文 msgId） */
    private String msgId;

    /** OUT 我方发出 / IN 平台来报 */
    private String direction;

    /** HANDOVER_NOTICE 交接通知 / HANDOVER_ACK 平台回执 */
    private String msgType;

    /** 关联订单号（归档目录键；无法识别为空时归 NO-ORDER 目录） */
    private String orderNo;

    /** 回执关联的原报文 msgId（IN） */
    private String refMsgId;

    /** 报文原文（重发/核查的唯一依据，逐字节保留） */
    private String content;

    /** 原文 SHA-256 */
    private String contentHash;

    /** 归档落盘路径（按订单号分目录） */
    private String filePath;

    /** GENERATED / RECEIVED */
    private String status;

    /** 重发次数（每次重发写副本文件并累计） */
    private Integer resendCount = 0;

    private LocalDateTime lastResendAt;

    /** 非库列：本次请求命中重复接收（同方向同 msgId 已归档） */
    @TableField(exist = false)
    private boolean duplicate;

    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getRefMsgId() { return refMsgId; }
    public void setRefMsgId(String refMsgId) { this.refMsgId = refMsgId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getResendCount() { return resendCount; }
    public void setResendCount(Integer resendCount) { this.resendCount = resendCount; }
    public LocalDateTime getLastResendAt() { return lastResendAt; }
    public void setLastResendAt(LocalDateTime lastResendAt) { this.lastResendAt = lastResendAt; }
    public boolean isDuplicate() { return duplicate; }
    public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }
}
