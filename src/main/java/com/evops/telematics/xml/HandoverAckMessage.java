package com.evops.telematics.xml;

import java.time.LocalDateTime;

/**
 * 平台回执报文（平台 -> 我方）：平台收到交接通知后的处理回执。
 *
 * 示例：
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <handoverAck msgId="ACK-88001" version="1.0">
 *   <orderNo>RO20260926001</orderNo>
 *   <refMsgId>HN1024-0001</refMsgId>
 *   <result>ACCEPTED</result>
 *   <reason/>
 *   <deviceNo>DEV-0001</deviceNo>
 *   <ackTime>2026-09-26 09:16:03</ackTime>
 * </handoverAck>
 * }</pre>
 */
@XmlRoot(name = "handoverAck")
public class HandoverAckMessage {

    public static final String MSG_TYPE = "HANDOVER_ACK";

    /** 平台侧报文唯一标识（重复接收幂等键） */
    @XmlAttribute(name = "msgId", required = true)
    private String msgId;

    @XmlAttribute(name = "version", required = true)
    private String version;

    @XmlElement(name = "orderNo", required = true)
    private String orderNo;

    /** 关联我方原报文 msgId */
    @XmlElement(name = "refMsgId", required = true)
    private String refMsgId;

    /** 处理结果：ACCEPTED 已受理 / REJECTED 已拒绝 */
    @XmlElement(name = "result", required = true, allowed = {"ACCEPTED", "REJECTED"})
    private String result;

    /** 拒绝原因等说明（可空） */
    @XmlElement(name = "reason")
    private String reason;

    /** 车载设备号（可空） */
    @XmlElement(name = "deviceNo")
    private String deviceNo;

    /** 平台处理时间 */
    @XmlElement(name = "ackTime", required = true)
    private LocalDateTime ackTime;

    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getRefMsgId() { return refMsgId; }
    public void setRefMsgId(String refMsgId) { this.refMsgId = refMsgId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }
    public LocalDateTime getAckTime() { return ackTime; }
    public void setAckTime(LocalDateTime ackTime) { this.ackTime = ackTime; }
}
