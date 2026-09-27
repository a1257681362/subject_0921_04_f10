package com.evops.telematics;

import com.evops.common.RequestContext;
import com.evops.rental.RentalHandover;
import com.evops.rental.RentalHandoverMapper;
import com.evops.telematics.xml.HandoverNoticeMessage;
import com.evops.telematics.xml.XmlCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 车载定位平台 XML 报文往来（telematics-2）集成测试：内存 H2，归档目录指向临时目录。
 *
 * 覆盖：
 * - 注解映射导出：按订单号归档、特殊字符转义、null 缺省与空串 <x/> 区分；
 * - 往返转换无丢失（含 null/空串/0/前后空格）；
 * - 接收：成功归档、同 msgId 幂等；校验不过 400 逐条明确错误并进异常清单、业务零写入；
 * - XML 语法错误带行列号、DOCTYPE 拒绝；
 * - 重发走归档原报文（业务数据变更后重发内容逐字节一致）、重发计数；
 * - 回归验证：上一期租车单全通过；脏数据单进异常清单、核销原因必填。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:telematics-xml-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.recon.enabled=false",
        "evops.telematics.xml-dir=${java.io.tmpdir}/evops-telematics-xml-test/xml"
})
class TelematicsXmlIntegrationTest {

    private static final String XML_DIR =
            System.getProperty("java.io.tmpdir") + "/evops-telematics-xml-test/xml";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RentalHandoverMapper handoverMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Operator", "dispatcher-li")
                .header("X-Request-No", reqNo())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode data(MvcResult result) throws Exception {
        // 用字节数组解析：响应无 charset 时 getContentAsString() 会按 ISO-8859-1 解码，中文变乱码
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }

    private long createVehicle(String plate) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"))
                        .content("{\"plateNo\":\"" + plate + "\",\"model\":\"Model-A\"}"))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("id").asLong();
    }

    /** 下单并交车，返回订单号；bizTime 同时作为下单与交接业务时间（回归按租车单业务时间过滤）。 */
    private String createOrderAndPickup(String orderNo, String branchCode, String bizTime,
                                        String remarkJson) throws Exception {
        long vehicleId = createVehicle("京X" + reqNo().substring(0, 6));
        String branch = branchCode != null ? ",\"branchCode\":\"" + branchCode + "\"" : "";
        mockMvc.perform(write(post("/api/orders"))
                        .header("X-Biz-Time", bizTime)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                                + ",\"customerName\":\"张三\"" + branch + "}"))
                .andExpect(status().isOk());
        MvcResult orders = mockMvc.perform(get("/api/orders").param("orderNo", orderNo))
                .andExpect(status().isOk()).andReturn();
        long orderId = data(orders).get("records").get(0).get("id").asLong();
        String remark = remarkJson != null ? ",\"remark\":" + remarkJson : "";
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"))
                        .header("X-Biz-Time", bizTime)
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a1.jpg,oss://p/a2.jpg\""
                                + remark + "}"))
                .andExpect(status().isOk());
        return orderNo;
    }

    private JsonNode exportByDate(String bizDate) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/telematics/xml/messages/export"))
                        .param("bizDate", bizDate))
                .andExpect(status().isOk()).andReturn();
        return data(result);
    }

    private JsonNode messageByOrderNo(String orderNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/telematics/xml/messages")
                        .param("orderNo", orderNo))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = data(result);
        assertEquals(1, list.size(), "应按订单号查到唯一报文：" + orderNo);
        return list.get(0);
    }

    /** 报文计数（direction/orderNo 可空即不过滤）：共享内存库下用前后差值断言，与用例顺序无关。 */
    private long countMessages(String direction, String orderNo) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/telematics/xml/messages");
        if (direction != null) {
            builder = builder.param("direction", direction);
        }
        if (orderNo != null) {
            builder = builder.param("orderNo", orderNo);
        }
        return data(mockMvc.perform(builder).andExpect(status().isOk()).andReturn()).size();
    }

    private long countOpenExceptions() throws Exception {
        return data(mockMvc.perform(get("/api/telematics/xml/exceptions")
                        .param("status", "OPEN"))
                .andExpect(status().isOk()).andReturn()).size();
    }

    /** 按 id 在异常清单（全状态）中找记录。 */
    private JsonNode findException(long exceptionId) throws Exception {
        JsonNode list = data(mockMvc.perform(get("/api/telematics/xml/exceptions"))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode item : list) {
            if (item.get("id").asLong() == exceptionId) {
                return item;
            }
        }
        throw new AssertionError("异常清单中未找到记录：" + exceptionId);
    }

    // =====================================================================
    // 导出：注解映射 / 按订单号归档 / 空值与缺省区分
    // =====================================================================

    @Test
    void exportArchivesByOrderNoAndDistinguishesNullFromEmpty() throws Exception {
        String bizDate = "2026-09-20";
        String tag = reqNo().substring(0, 6);
        // 单 1：备注含 XML 特殊字符 < > & "，网点 BR01
        createOrderAndPickup("RO-X1-" + tag, "BR01", bizDate + "T09:15:00",
                "\"前杠<划痕> & \\\"掉漆\\\"，已拍照\"");
        // 单 2：备注空串 -> <remark/>
        createOrderAndPickup("RO-X2-" + tag, null, bizDate + "T10:20:00", "\"\"");
        // 单 3：备注不传 -> null -> 元素缺省
        createOrderAndPickup("RO-X3-" + tag, null, bizDate + "T11:25:00", null);

        JsonNode summary = exportByDate(bizDate);
        assertEquals(3, summary.get("generatedCount").asInt());
        assertEquals(0, summary.get("failedCount").asInt());

        // 单 1：特殊字符转义；按订单号分目录归档且文件真实存在
        JsonNode msg1 = messageByOrderNo("RO-X1-" + tag);
        String xml1 = msg1.get("content").asText();
        assertTrue(xml1.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "应有 XML 声明：" + xml1);
        assertTrue(xml1.contains("<handoverNotice msgId=\"HN"), "根元素与 msgId 属性：" + xml1);
        assertTrue(xml1.contains("version=\"1.0\""), "version 属性：" + xml1);
        assertTrue(xml1.contains("<orderNo>RO-X1-" + tag + "</orderNo>"), xml1);
        assertTrue(xml1.contains("<handoverType>PICKUP</handoverType>"), xml1);
        assertTrue(xml1.contains("<handoverTime>2026-09-20 09:15:00</handoverTime>"), xml1);
        assertTrue(xml1.contains("<fuelReading>80</fuelReading>"), xml1);
        assertTrue(xml1.contains("<branchCode>BR01</branchCode>"), xml1);
        assertTrue(xml1.contains("前杠&lt;划痕&gt; &amp; \"掉漆\"，已拍照"),
                "< > & 应转义、文本内引号不转义：" + xml1);
        Path archived1 = Paths.get(msg1.get("filePath").asText());
        assertTrue(archived1.startsWith(Paths.get(XML_DIR, "RO-X1-" + tag)),
                "应按订单号分目录归档：" + archived1);
        assertTrue(Files.exists(archived1), "归档文件应真实存在：" + archived1);
        assertEquals(xml1, new String(Files.readAllBytes(archived1), StandardCharsets.UTF_8),
                "落盘文件应与库内原文一致");

        // 单 2：空串备注 -> <remark/>；未分派网点 -> branchCode 元素缺省
        JsonNode msg2 = messageByOrderNo("RO-X2-" + tag);
        String xml2 = msg2.get("content").asText();
        assertTrue(xml2.contains("<remark/>"), "空串备注应为自闭合元素：" + xml2);
        assertFalse(xml2.contains("branchCode"), "null 网点应元素缺省：" + xml2);

        // 单 3：null 备注 -> 元素整体缺省（不补空串、不补默认值）
        JsonNode msg3 = messageByOrderNo("RO-X3-" + tag);
        assertFalse(msg3.get("content").asText().contains("remark"),
                "null 备注应元素缺省：" + msg3.get("content").asText());

        // 下载即归档原文
        MvcResult download = mockMvc.perform(get("/api/telematics/xml/messages/"
                        + msg1.get("id").asLong() + "/download"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(xml1, new String(download.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8));
    }

    // =====================================================================
    // 往返转换无丢失（编解码器级）
    // =====================================================================

    @Test
    void roundTripIsLosslessAndNeverDefaultsNull() {
        HandoverNoticeMessage notice = new HandoverNoticeMessage();
        notice.setMsgId("HN1-0001");
        notice.setVersion("1.0");
        notice.setOrderNo("RO-RT-1");
        notice.setPlateNo("京A12345");
        notice.setHandoverType("PICKUP");
        notice.setHandoverTime(LocalDateTime.of(2026, 9, 26, 9, 15, 0));
        notice.setFuelReading(null);                 // 缺省：不补 0
        notice.setDamagePhotos("");                  // 空串：不是 null
        notice.setRemark("  前后空格保留  ");          // 内容原样不去空白
        notice.setBranchCode(null);

        String xml = XmlCodec.marshal(notice);
        assertFalse(xml.contains("fuelReading"), "null 数值应缺省而非 0：" + xml);
        assertTrue(xml.contains("<damagePhotos/>"), "空串应为自闭合元素：" + xml);
        assertTrue(xml.contains("<remark>  前后空格保留  </remark>"), "空白应原样保留：" + xml);

        HandoverNoticeMessage restored = XmlCodec.unmarshal(xml, HandoverNoticeMessage.class);
        assertNull(restored.getFuelReading(), "缺省解析应为 null 而非 0");
        assertEquals("", restored.getDamagePhotos(), "<x/> 应解析为空串而非 null");
        assertEquals("  前后空格保留  ", restored.getRemark());
        assertNull(restored.getBranchCode());
        assertTrue(XmlCodec.diffMappedFields(notice, restored).isEmpty(),
                "往返转换应无丢失");

        // 0 与 null 是两种值：0 正常出站入站，互不串味
        notice.setFuelReading(0);
        String xmlZero = XmlCodec.marshal(notice);
        assertTrue(xmlZero.contains("<fuelReading>0</fuelReading>"), xmlZero);
        assertEquals(0, XmlCodec.unmarshal(xmlZero, HandoverNoticeMessage.class).getFuelReading());
    }

    // =====================================================================
    // 接收：成功归档 / 重复接收幂等
    // =====================================================================

    private static String ackXml(String msgId, String orderNo, String result) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<handoverAck msgId=\"" + msgId + "\" version=\"1.0\">\n"
                + "  <orderNo>" + orderNo + "</orderNo>\n"
                + "  <refMsgId>HN1-0001</refMsgId>\n"
                + "  <result>" + result + "</result>\n"
                + "  <deviceNo>DEV-0001</deviceNo>\n"
                + "  <ackTime>2026-09-26 09:16:03</ackTime>\n"
                + "</handoverAck>\n";
    }

    private MockHttpServletRequestBuilder receiveXml(String xml) {
        return post("/api/telematics/xml/messages/receive")
                .header("X-Operator", "dispatcher-li")
                .header("X-Request-No", reqNo())
                .contentType("text/xml;charset=UTF-8")
                .content(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void receiveAckSuccessAndDuplicateIsIdempotent() throws Exception {
        String tag = reqNo().substring(0, 6);
        String xml = ackXml("ACK-" + tag, "RO-IN-" + tag, "ACCEPTED");

        MvcResult first = mockMvc.perform(receiveXml(xml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.msgId").value("ACK-" + tag))
                .andExpect(jsonPath("$.data.orderNo").value("RO-IN-" + tag))
                .andExpect(jsonPath("$.data.refMsgId").value("HN1-0001"))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn();
        JsonNode record = data(first);

        // 原文逐字节归档：库内原文 = 来报原文；按订单号落盘
        assertEquals(xml, record.get("content").asText());
        Path archived = Paths.get(record.get("filePath").asText());
        assertTrue(archived.startsWith(Paths.get(XML_DIR, "RO-IN-" + tag)));
        assertEquals(xml, new String(Files.readAllBytes(archived), StandardCharsets.UTF_8));

        // 同 msgId 重复接收（换请求号）：幂等返回首次结果，不重复归档
        MvcResult second = mockMvc.perform(receiveXml(xml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.id").value(record.get("id").asLong()))
                .andReturn();
        assertEquals(record.get("id").asLong(), data(second).get("id").asLong());
        mockMvc.perform(get("/api/telematics/xml/messages")
                        .param("direction", "IN").param("orderNo", "RO-IN-" + tag))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // =====================================================================
    // 接收失败：明确错误 + 异常清单 + 业务零写入
    // =====================================================================

    @Test
    void receiveInvalidAckReturnsClearErrorsAndGoesToExceptionList() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<handoverAck version=\"1.0\">\n"
                + "  <refMsgId>HN1-0001</refMsgId>\n"
                + "  <result>MAYBE</result>\n"
                + "  <ackTime>26/09/2026 09:16</ackTime>\n"
                + "</handoverAck>\n";

        long inboundBefore = countMessages("IN", null);
        MvcResult result = mockMvc.perform(receiveXml(xml))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("XML_MESSAGE_INVALID"))
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        JsonNode errors = response.get("data").get("errors");
        String all = errors.toString();
        assertTrue(all.contains("属性 msgId"), "应指出 msgId 属性缺失：" + all);
        assertTrue(all.contains("元素 orderNo"), "应指出 orderNo 缺失：" + all);
        assertTrue(all.contains("result") && all.contains("ACCEPTED/REJECTED"),
                "应指出取值白名单：" + all);
        assertTrue(all.contains("ackTime") && all.contains("yyyy-MM-dd HH:mm:ss"),
                "应指出时间格式：" + all);
        long exceptionId = response.get("data").get("exceptionId").asLong();
        assertTrue(exceptionId > 0, "应已进异常清单");

        // 异常清单可查：来源 INBOUND、错误明细、原文留存、待处置
        JsonNode exception = findException(exceptionId);
        assertEquals("INBOUND", exception.get("source").asText());
        assertEquals("OPEN", exception.get("status").asText());
        assertTrue(exception.get("errorDetail").asText().contains("orderNo"),
                "错误明细应含元素路径：" + exception.get("errorDetail").asText());
        assertTrue(exception.get("rawContent").asText().contains("MAYBE"),
                "原文应留存供排障");

        // 业务零写入：来报未归档（前后计数不变，与用例执行顺序无关）
        assertEquals(inboundBefore, countMessages("IN", null));
    }

    @Test
    void receiveMalformedXmlAndDoctypeAreRejectedClearly() throws Exception {
        long openBefore = countOpenExceptions();

        // 语法错误：报行列号
        MvcResult malformed = mockMvc.perform(receiveXml(
                        "<handoverAck msgId=\"A1\"><orderNo>RO-1</orderNo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("XML_MESSAGE_INVALID"))
                .andReturn();
        String malformedMsg = new String(malformed.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertTrue(malformedMsg.contains("第") && malformedMsg.contains("行"),
                "语法错误应带行列号：" + malformedMsg);

        // DOCTYPE：明确拒绝（防 XXE）
        MvcResult doctype = mockMvc.perform(receiveXml(
                        "<?xml version=\"1.0\"?>\n<!DOCTYPE handoverAck "
                                + "[<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                                + "<handoverAck msgId=\"A2\"/>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("DOCTYPE")))
                .andReturn();
        assertEquals(400, doctype.getResponse().getStatus());

        // 两笔失败都进异常清单（前后计数差，与用例执行顺序无关）
        assertEquals(openBefore + 2, countOpenExceptions());
    }

    // =====================================================================
    // 重发：走归档原报文
    // =====================================================================

    @Test
    void resendUsesOriginalArchivedMessage() throws Exception {
        String bizDate = "2026-09-21";
        String tag = reqNo().substring(0, 6);
        String orderNo = createOrderAndPickup("RO-RS-" + tag, null, bizDate + "T08:30:00",
                "\"原备注\"");
        JsonNode summary = exportByDate(bizDate);
        assertEquals(1, summary.get("generatedCount").asInt());
        JsonNode message = messageByOrderNo(orderNo);
        long messageId = message.get("id").asLong();
        String original = message.get("content").asText();

        // 业务数据变更（备注改写）：重发不得重新生成
        List<RentalHandover> handovers = handoverMapper.selectList(null);
        RentalHandover handover = handovers.stream()
                .filter(h -> "原备注".equals(h.getRemark())).findFirst().orElseThrow(
                        () -> new IllegalStateException("交接记录不存在"));
        handover.setRemark("已改备注");
        handoverMapper.updateById(handover);

        // 重发：内容与原报文逐字节一致
        MvcResult resent = mockMvc.perform(write(post(
                        "/api/telematics/xml/messages/" + messageId + "/resend")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resendCount").value(1))
                .andExpect(jsonPath("$.data.lastResendAt").isNotEmpty())
                .andReturn();
        JsonNode resentRecord = data(resent);
        assertEquals(original, resentRecord.get("content").asText(),
                "重发应走归档原报文，不受业务数据变更影响");
        assertFalse(resentRecord.get("content").asText().contains("已改备注"));

        // 重发副本落盘：内容同为原报文
        Path resendCopy = Paths.get(XML_DIR, orderNo,
                "resend_" + message.get("msgId").asText() + "_1.xml");
        assertTrue(Files.exists(resendCopy), "重发副本应落盘：" + resendCopy);
        assertEquals(original, new String(Files.readAllBytes(resendCopy), StandardCharsets.UTF_8));

        // 再次重发计数累计；来报不可重发
        mockMvc.perform(write(post("/api/telematics/xml/messages/" + messageId + "/resend")))
                .andExpect(jsonPath("$.data.resendCount").value(2));
        String ack = ackXml("ACK-RS-" + tag, orderNo, "ACCEPTED");
        long inboundId = data(mockMvc.perform(receiveXml(ack))
                .andExpect(status().isOk()).andReturn()).get("id").asLong();
        mockMvc.perform(write(post("/api/telematics/xml/messages/" + inboundId + "/resend")))
                .andExpect(status().isConflict());
    }

    // =====================================================================
    // 回归验证：上一期租车单往返比对
    // =====================================================================

    @Test
    void regressionPassesForLastPeriodOrders() throws Exception {
        String tag = reqNo().substring(0, 6);
        String branch = "BR-RG-" + tag;
        createOrderAndPickup("RO-RG1-" + tag, branch, "2026-09-18T09:00:00", "\"一期正常单\"");
        createOrderAndPickup("RO-RG2-" + tag, branch, "2026-09-19T10:00:00", null);

        MvcResult result = mockMvc.perform(write(post("/api/telematics/xml/regression"))
                        .content("{\"branchCode\":\"" + branch + "\","
                                + "\"periodFrom\":\"2026-09-18\",\"periodTo\":\"2026-09-19\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.totalOrders").value(2))
                .andExpect(jsonPath("$.data.totalMessages").value(2))
                .andExpect(jsonPath("$.data.passedCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andReturn();

        long runId = data(result).get("id").asLong();
        mockMvc.perform(get("/api/telematics/xml/regression/" + runId))
                .andExpect(jsonPath("$.data.branchCode").value(branch));
        mockMvc.perform(get("/api/telematics/xml/exceptions").param("runId", String.valueOf(runId)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void regressionFlagsDirtyLegacyDataIntoExceptionList() throws Exception {
        String tag = reqNo().substring(0, 6);
        String branch = "BR-DT-" + tag;
        // 正常单：走接口
        createOrderAndPickup("RO-DT1-" + tag, branch, "2026-09-18T09:00:00", "\"正常\"");
        // 脏数据单：上一期历史数据 remark 含 XML 1.0 非法控制字符（直插库模拟，绕过入口校验）
        long vehicleId = createVehicle("京D" + tag);
        mockMvc.perform(write(post("/api/orders"))
                        .header("X-Biz-Time", "2026-09-18T08:00:00")
                        .content("{\"orderNo\":\"RO-DT2-" + tag + "\",\"vehicleId\":" + vehicleId
                                + ",\"customerName\":\"李四\",\"branchCode\":\"" + branch + "\"}"))
                .andExpect(status().isOk());
        MvcResult orders = mockMvc.perform(get("/api/orders").param("orderNo", "RO-DT2-" + tag))
                .andExpect(status().isOk()).andReturn();
        long dirtyOrderId = data(orders).get("records").get(0).get("id").asLong();
        RentalHandover dirty = new RentalHandover();
        dirty.setOrderId(dirtyOrderId);
        dirty.setType("PICKUP");
        dirty.setFuelReading(50);
        dirty.setDamagePhotos("oss://p/old.jpg");
        dirty.setRemark("历史脏数据\u0001备注");
        dirty.setBizTime(LocalDateTime.of(2026, 9, 18, 8, 30, 0));
        RequestContext.set("dispatcher-li", LocalDateTime.of(2026, 9, 18, 8, 30, 0),
                "req-dirty-" + tag);
        try {
            handoverMapper.insert(dirty);
        } finally {
            RequestContext.clear();
        }

        // 回归：脏数据单失败进异常清单，正常单通过，整批 FAILED
        MvcResult result = mockMvc.perform(write(post("/api/telematics/xml/regression"))
                        .content("{\"branchCode\":\"" + branch + "\","
                                + "\"periodFrom\":\"2026-09-18\",\"periodTo\":\"2026-09-19\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.totalOrders").value(2))
                .andExpect(jsonPath("$.data.totalMessages").value(2))
                .andExpect(jsonPath("$.data.passedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andReturn();
        long runId = data(result).get("id").asLong();

        MvcResult exceptions = mockMvc.perform(get("/api/telematics/xml/exceptions")
                        .param("runId", String.valueOf(runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].source").value("REGRESSION"))
                .andExpect(jsonPath("$.data[0].orderNo").value("RO-DT2-" + tag))
                .andExpect(jsonPath("$.data[0].errorDetail", containsString("非法字符")))
                .andReturn();
        long exceptionId = data(exceptions).get(0).get("id").asLong();

        // 核销：原因必填；核销后状态与留痕；重复核销拒绝
        mockMvc.perform(write(post("/api/telematics/xml/exceptions/" + exceptionId + "/resolve"))
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/telematics/xml/exceptions/" + exceptionId + "/resolve"))
                        .content("{\"reason\":\"源头已清洗，重新导出确认通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolveOperator").value("dispatcher-li"))
                .andExpect(jsonPath("$.data.resolveTime").isNotEmpty());
        mockMvc.perform(write(post("/api/telematics/xml/exceptions/" + exceptionId + "/resolve"))
                        .content("{\"reason\":\"重复核销\"}"))
                .andExpect(status().isConflict());
    }
}
