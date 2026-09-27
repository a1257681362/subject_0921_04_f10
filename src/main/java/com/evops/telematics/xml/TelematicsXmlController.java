package com.evops.telematics.xml;

import com.evops.common.ApiResponse;
import com.evops.telematics.TelematicsProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 车载定位平台 XML 报文往接口。写请求统一携带 X-Operator / X-Request-No（/X-Biz-Time）
 * 三件套，过幂等拦截。
 */
@RestController
@RequestMapping("/api/telematics/xml")
public class TelematicsXmlController {

    private final TelematicsXmlService xmlService;
    private final TelematicsProperties properties;

    public TelematicsXmlController(TelematicsXmlService xmlService, TelematicsProperties properties) {
        this.xmlService = xmlService;
        this.properties = properties;
    }

    // ---- 发送（我方 -> 平台） ----

    /** 生成指定业务日（缺省当天）的交接通知报文并按订单号归档；单条脏数据进异常清单后继续。 */
    @PostMapping("/messages/export")
    public ApiResponse<Map<String, Object>> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        LocalDate target = bizDate != null ? bizDate : LocalDate.now();
        return ApiResponse.ok(xmlService.exportHandoverNotices(target));
    }

    /** 重发：走归档原报文（逐字节一致），resend_count 累计。 */
    @PostMapping("/messages/{id}/resend")
    public ApiResponse<TelematicsXmlMessage> resend(@PathVariable Long id) {
        return ApiResponse.ok(xmlService.resend(id));
    }

    // ---- 接收（平台 -> 我方） ----

    /** 接收平台回执 XML。校验不过返回 400 逐条明确错误并进异常清单；同 msgId 重复接收幂等。 */
    @PostMapping(value = "/messages/receive",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ApiResponse<TelematicsXmlMessage> receive(@RequestBody String xml) {
        return ApiResponse.ok(xmlService.receiveAck(xml));
    }

    // ---- 归档查询 ----

    @GetMapping("/messages")
    public ApiResponse<List<TelematicsXmlMessage>> listMessages(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String direction) {
        return ApiResponse.ok(xmlService.listMessages(orderNo, direction));
    }

    @GetMapping("/messages/{id}")
    public ApiResponse<TelematicsXmlMessage> getMessage(@PathVariable Long id) {
        return ApiResponse.ok(xmlService.requireMessage(id));
    }

    /** 下载归档原报文（重发/核查口径：内容即归档原文）。 */
    @GetMapping("/messages/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        TelematicsXmlMessage message = xmlService.requireMessage(id);
        byte[] content = message.getContent().getBytes(properties.charsetObj());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + message.getMsgId() + ".xml\"")
                .contentType(MediaType.parseMediaType(
                        "application/xml;charset=" + properties.getCharset()))
                .body(content);
    }

    // ---- 异常清单 ----

    @GetMapping("/exceptions")
    public ApiResponse<List<XmlExceptionRecord>> listExceptions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long runId) {
        return ApiResponse.ok(xmlService.listExceptions(status, runId));
    }

    /** 核销异常：原因必填。 */
    @PostMapping("/exceptions/{id}/resolve")
    public ApiResponse<XmlExceptionRecord> resolveException(@PathVariable Long id,
                                                            @Validated @RequestBody XmlResolveRequest request) {
        return ApiResponse.ok(xmlService.resolveException(id, request.getReason()));
    }

    // ---- 回归验证 ----

    /** 上线前回归验证：用该网点上一期租车单走一轮往返比对，失败明细进异常清单。 */
    @PostMapping("/regression")
    public ApiResponse<TelematicsXmlRegression> regression(
            @Validated @RequestBody XmlRegressionRequest request) {
        return ApiResponse.ok(xmlService.runRegression(
                request.getBranchCode().trim(), request.getPeriodFrom(), request.getPeriodTo()));
    }

    @GetMapping("/regression")
    public ApiResponse<List<TelematicsXmlRegression>> listRegressions() {
        return ApiResponse.ok(xmlService.listRegressions());
    }

    @GetMapping("/regression/{id}")
    public ApiResponse<TelematicsXmlRegression> getRegression(@PathVariable Long id) {
        return ApiResponse.ok(xmlService.requireRegression(id));
    }
}
