package com.evops.telematics.xml;

import java.time.LocalDateTime;

/**
 * 交接通知报文（我方 -> 平台）：交车/还车时通知平台启停定位跟踪。
 * 数据列与 telematics-1 CSV 导出口径对齐，另补归属网点。
 *
 * 示例：
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <handoverNotice msgId="HN1024-0001" version="1.0">
 *   <orderNo>RO20260926001</orderNo>
 *   <plateNo>京A12345</plateNo>
 *   <handoverType>PICKUP</handoverType>
 *   <handoverTime>2026-09-26 09:15:00</handoverTime>
 *   <fuelReading>80</fuelReading>
 *   <damagePhotos>oss://p/a1.jpg,oss://p/a2.jpg</damagePhotos>
 *   <remark/>
 *   <branchCode>BJ01</branchCode>
 * </handoverNotice>
 * }</pre>
 */
@XmlRoot(name = "handoverNotice")
public class HandoverNoticeMessage {

    public static final String MSG_TYPE = "HANDOVER_NOTICE";

    /** 报文唯一标识（我方生成，重发沿用原报文不换新） */
    @XmlAttribute(name = "msgId", required = true)
    private String msgId;

    /** 报文规范版本 */
    @XmlAttribute(name = "version", required = true)
    private String version;

    @XmlElement(name = "orderNo", required = true)
    private String orderNo;

    @XmlElement(name = "plateNo", required = true)
    private String plateNo;

    /** PICKUP 交车 / RETURN 还车 */
    @XmlElement(name = "handoverType", required = true, allowed = {"PICKUP", "RETURN"})
    private String handoverType;

    /** 交接业务时间 */
    @XmlElement(name = "handoverTime", required = true)
    private LocalDateTime handoverTime;

    /** 油量表读数（0-100）；可空字段，缺省不补 0 */
    @XmlElement(name = "fuelReading")
    private Integer fuelReading;

    /** 车损照片地址，多个以逗号分隔 */
    @XmlElement(name = "damagePhotos")
    private String damagePhotos;

    /** 现场备注；null 缺省 / 空串 <remark/> 区分 */
    @XmlElement(name = "remark")
    private String remark;

    /** 归属网点编码（可空=未分派） */
    @XmlElement(name = "branchCode")
    private String branchCode;

    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getHandoverType() { return handoverType; }
    public void setHandoverType(String handoverType) { this.handoverType = handoverType; }
    public LocalDateTime getHandoverTime() { return handoverTime; }
    public void setHandoverTime(LocalDateTime handoverTime) { this.handoverTime = handoverTime; }
    public Integer getFuelReading() { return fuelReading; }
    public void setFuelReading(Integer fuelReading) { this.fuelReading = fuelReading; }
    public String getDamagePhotos() { return damagePhotos; }
    public void setDamagePhotos(String damagePhotos) { this.damagePhotos = damagePhotos; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
}
