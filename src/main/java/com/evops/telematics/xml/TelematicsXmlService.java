package com.evops.telematics.xml;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.rental.RentalHandover;
import com.evops.rental.RentalHandoverMapper;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderMapper;
import com.evops.telematics.TelematicsProperties;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车载定位平台 XML 报文往来（telematics-2）。
 *
 * 发送（我方 -> 平台）：按业务日抽取交接记录组交接通知报文，注解映射序列化，
 * 原文落库 + 按订单号分目录落盘归档。重发读归档原报文，逐字节一致，不重新生成。
 *
 * 接收（平台 -> 我方）：平台回执 XML 解析校验；校验不过进异常清单（独立事务，
 * 不随请求回滚）并返回逐条明确错误；同 msgId 重复接收幂等。
 *
 * 回归验证：用网点上一期租车单走一轮“组报文 -> XML -> 解析回对象”往返比对，
 * 失败明细进异常清单（挂回归批次），批次汇总落库。
 */
@Service
public class TelematicsXmlService {

    /** 报文规范版本（与对方对齐的口径） */
    public static final String SPEC_VERSION = "1.0";

    /** 订单号缺失时的归档目录哨兵（与 NO-BRANCH 同风格） */
    private static final String NO_ORDER_DIR = "NO-ORDER";

    /** 归档目录名安全化：订单号可手工传入，目录名只留安全字符 */
    private static final Pattern UNSAFE_DIR_CHARS = Pattern.compile("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]");

    /** 来报解析失败时尽力从原文提取订单号（仅用于异常清单归集） */
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("<orderNo>([^<]{1,40})</orderNo>");

    private final TelematicsProperties properties;
    private final TelematicsXmlMessageMapper messageMapper;
    private final XmlExceptionRecordMapper exceptionMapper;
    private final TelematicsXmlRegressionMapper regressionMapper;
    private final RentalHandoverMapper handoverMapper;
    private final RentalOrderMapper orderMapper;
    private final VehicleMapper vehicleMapper;
    private final TelematicsXmlService self;

    public TelematicsXmlService(TelematicsProperties properties,
                                TelematicsXmlMessageMapper messageMapper,
                                XmlExceptionRecordMapper exceptionMapper,
                                TelematicsXmlRegressionMapper regressionMapper,
                                RentalHandoverMapper handoverMapper,
                                RentalOrderMapper orderMapper,
                                VehicleMapper vehicleMapper,
                                @Lazy TelematicsXmlService self) {
        this.properties = properties;
        this.messageMapper = messageMapper;
        this.exceptionMapper = exceptionMapper;
        this.regressionMapper = regressionMapper;
        this.handoverMapper = handoverMapper;
        this.orderMapper = orderMapper;
        this.vehicleMapper = vehicleMapper;
        this.self = self;
    }

    // =====================================================================
    // 发送（我方 -> 平台）：交接通知报文按订单号归档
    // =====================================================================

    /**
     * 生成指定业务日（按交接业务时间）的交接通知报文并归档：一交接一条，
     * 原文落库 + 按订单号分目录落盘。单条组报文失败（脏数据）进异常清单后继续，
     * 系统错误（落盘失败等）整批回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> exportHandoverNotices(LocalDate bizDate) {
        LocalDateTime from = bizDate.atStartOfDay();
        LocalDateTime to = bizDate.plusDays(1).atStartOfDay();
        List<RentalHandover> handovers = handoverMapper.selectList(
                new LambdaQueryWrapper<RentalHandover>()
                        .ge(RentalHandover::getBizTime, from)
                        .lt(RentalHandover::getBizTime, to)
                        .orderByAsc(RentalHandover::getBizTime)
                        .orderByAsc(RentalHandover::getId));

        List<TelematicsXmlMessage> generated = new ArrayList<>();
        int failed = 0;
        for (RentalHandover handover : handovers) {
            RentalOrder order = orderMapper.selectById(handover.getOrderId());
            Vehicle vehicle = order != null ? vehicleMapper.selectById(order.getVehicleId()) : null;
            HandoverNoticeMessage notice = buildNotice(order, vehicle, handover,
                    "HN" + handover.getId() + "-" + String.format("%04d",
                            ThreadLocalRandom.current().nextInt(10000)));
            String xml;
            try {
                xml = XmlCodec.marshal(notice, properties.getCharset());
            } catch (XmlFormatException | XmlValidationException ex) {
                // 脏数据组不出合法报文：进异常清单（独立事务），不拖垮整批导出
                self.recordException(XmlExceptionRecord.SOURCE_EXPORT,
                        TelematicsXmlMessage.DIRECTION_OUT, HandoverNoticeMessage.MSG_TYPE,
                        notice.getOrderNo(), null, ex.getMessage(), null);
                failed++;
                continue;
            }
            generated.add(archiveMessage(TelematicsXmlMessage.DIRECTION_OUT,
                    HandoverNoticeMessage.MSG_TYPE, notice.getMsgId(), notice.getOrderNo(),
                    null, xml, TelematicsXmlMessage.STATUS_GENERATED));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("bizDate", bizDate.toString());
        summary.put("generatedCount", generated.size());
        summary.put("failedCount", failed);
        summary.put("messages", generated);
        return summary;
    }

    /** 组交接通知报文（导出与回归验证共用同一组装口径）。 */
    private HandoverNoticeMessage buildNotice(RentalOrder order, Vehicle vehicle,
                                              RentalHandover handover, String msgId) {
        HandoverNoticeMessage notice = new HandoverNoticeMessage();
        notice.setMsgId(msgId);
        notice.setVersion(SPEC_VERSION);
        notice.setOrderNo(order != null ? order.getOrderNo() : null);
        notice.setPlateNo(vehicle != null ? vehicle.getPlateNo() : null);
        notice.setHandoverType(handover.getType());
        // 报文时间口径为秒级（与 telematics-1 一致）：截断纳秒，保证回归往返比对不因精度误报
        notice.setHandoverTime(handover.getBizTime() != null
                ? handover.getBizTime().withNano(0) : null);
        notice.setFuelReading(handover.getFuelReading());
        notice.setDamagePhotos(handover.getDamagePhotos());
        notice.setRemark(handover.getRemark());
        notice.setBranchCode(order != null ? order.getBranchCode() : null);
        return notice;
    }

    // =====================================================================
    // 重发：走归档原报文，逐字节一致
    // =====================================================================

    /**
     * 重发：从归档记录取原报文原文写重发副本（内容逐字节一致，绝不从当前业务数据
     * 重新生成），resend_count 累计。仅 OUT 报文可重发。
     */
    @Transactional(rollbackFor = Exception.class)
    public TelematicsXmlMessage resend(Long id) {
        TelematicsXmlMessage message = requireMessage(id);
        if (!TelematicsXmlMessage.DIRECTION_OUT.equals(message.getDirection())) {
            throw new BizException("仅我方发出的报文可重发：" + id);
        }
        int seq = (message.getResendCount() == null ? 0 : message.getResendCount()) + 1;
        // 原报文逐字节落重发副本；平台按约定从归档目录取件即完成重发
        writeArchiveFile(message.getOrderNo(),
                "resend_" + message.getMsgId() + "_" + seq + ".xml", message.getContent());
        message.setResendCount(seq);
        message.setLastResendAt(LocalDateTime.now());
        messageMapper.updateById(message);
        return message;
    }

    // =====================================================================
    // 接收（平台 -> 我方）：回执解析校验，失败进异常清单
    // =====================================================================

    /**
     * 接收平台回执。校验不过：进异常清单（独立事务）并抛 {@link XmlInboundException}
     * （逐条明确错误）；同 msgId 重复接收幂等，返回首次归档结果（duplicate=true）。
     */
    @Transactional(rollbackFor = Exception.class)
    public TelematicsXmlMessage receiveAck(String xml) {
        HandoverAckMessage ack;
        try {
            ack = XmlCodec.unmarshal(xml, HandoverAckMessage.class);
        } catch (XmlValidationException ex) {
            throw failInbound(ex.getErrors(), xml);
        } catch (XmlFormatException ex) {
            throw failInbound(Collections.singletonList(ex.getMessage()), xml);
        }

        TelematicsXmlMessage existing = messageMapper.selectOne(
                new LambdaQueryWrapper<TelematicsXmlMessage>()
                        .eq(TelematicsXmlMessage::getDirection, TelematicsXmlMessage.DIRECTION_IN)
                        .eq(TelematicsXmlMessage::getMsgId, ack.getMsgId()));
        if (existing != null) {
            existing.setDuplicate(true);
            return existing;
        }

        return archiveMessage(TelematicsXmlMessage.DIRECTION_IN, HandoverAckMessage.MSG_TYPE,
                ack.getMsgId(), ack.getOrderNo(), ack.getRefMsgId(), xml,
                TelematicsXmlMessage.STATUS_RECEIVED);
    }

    /** 来报失败：异常清单独立事务落库（不随请求回滚），返回携带明确错误的异常。 */
    private XmlInboundException failInbound(List<String> errors, String xml) {
        Long exceptionId = self.recordException(XmlExceptionRecord.SOURCE_INBOUND,
                TelematicsXmlMessage.DIRECTION_IN, HandoverAckMessage.MSG_TYPE,
                extractOrderNo(xml), null, String.join("\n", errors), xml);
        return new XmlInboundException(errors, exceptionId);
    }

    private static String extractOrderNo(String xml) {
        if (xml == null) {
            return null;
        }
        Matcher matcher = ORDER_NO_PATTERN.matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    // =====================================================================
    // 回归验证：网点上一期租车单走一轮往返比对
    // =====================================================================

    /**
     * 回归验证：抽取该网点期间内（按租车单业务时间）的租车单及其交接记录，
     * 逐条“组报文 -> XML -> 解析回对象”往返比对。失败明细进异常清单（挂 run_id），
     * 批次汇总落库；失败不影响其余单继续验证。
     */
    public TelematicsXmlRegression runRegression(String branchCode, LocalDate periodFrom,
                                                 LocalDate periodTo) {
        if (periodFrom.isAfter(periodTo)) {
            throw new IllegalArgumentException("期间起不能晚于期间止：" + periodFrom + " > " + periodTo);
        }
        List<RentalOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<RentalOrder>()
                .eq(RentalOrder::getBranchCode, branchCode)
                .ge(RentalOrder::getBizTime, periodFrom.atStartOfDay())
                .lt(RentalOrder::getBizTime, periodTo.plusDays(1).atStartOfDay())
                .orderByAsc(RentalOrder::getId));

        TelematicsXmlRegression run = new TelematicsXmlRegression();
        run.setBranchCode(branchCode);
        run.setPeriodFrom(periodFrom);
        run.setPeriodTo(periodTo);
        run.setTotalOrders(orders.size());
        self.insertRegression(run);

        int total = 0;
        int passed = 0;
        int failed = 0;
        for (RentalOrder order : orders) {
            Vehicle vehicle = vehicleMapper.selectById(order.getVehicleId());
            List<RentalHandover> handovers = handoverMapper.selectList(
                    new LambdaQueryWrapper<RentalHandover>()
                            .eq(RentalHandover::getOrderId, order.getId())
                            .orderByAsc(RentalHandover::getId));
            for (RentalHandover handover : handovers) {
                total++;
                HandoverNoticeMessage notice = buildNotice(order, vehicle, handover,
                        "RG" + run.getId() + "-" + handover.getId());
                List<String> diffs = roundTrip(notice);
                if (diffs.isEmpty()) {
                    passed++;
                } else {
                    failed++;
                    self.recordException(XmlExceptionRecord.SOURCE_REGRESSION,
                            TelematicsXmlMessage.DIRECTION_OUT, HandoverNoticeMessage.MSG_TYPE,
                            order.getOrderNo(), run.getId(), String.join("\n", diffs),
                            safeMarshal(notice));
                }
            }
        }
        run.setTotalMessages(total);
        run.setPassedCount(passed);
        run.setFailedCount(failed);
        run.setStatus(failed > 0
                ? TelematicsXmlRegression.STATUS_FAILED : TelematicsXmlRegression.STATUS_SUCCESS);
        self.finishRegression(run);
        return run;
    }

    /** 往返比对：序列化 -> 解析回对象 -> 逐映射字段比对，返回差异（空 = 无丢失）。 */
    private List<String> roundTrip(HandoverNoticeMessage notice) {
        String xml;
        try {
            xml = XmlCodec.marshal(notice, properties.getCharset());
        } catch (XmlFormatException | XmlValidationException ex) {
            return Collections.singletonList("组报文失败：" + ex.getMessage());
        }
        HandoverNoticeMessage restored;
        try {
            restored = XmlCodec.unmarshal(xml, HandoverNoticeMessage.class);
        } catch (XmlFormatException | XmlValidationException ex) {
            return Collections.singletonList("回读解析失败：" + ex.getMessage());
        }
        return XmlCodec.diffMappedFields(notice, restored);
    }

    /** 回归留证用：尽力组出报文原文，组不出来返回 null（差异描述已含原因）。 */
    private String safeMarshal(HandoverNoticeMessage notice) {
        try {
            return XmlCodec.marshal(notice, properties.getCharset());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void insertRegression(TelematicsXmlRegression run) {
        regressionMapper.insert(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishRegression(TelematicsXmlRegression run) {
        regressionMapper.updateById(run);
    }

    // =====================================================================
    // 异常清单
    // =====================================================================

    /** 异常清单落库：独立事务（REQUIRES_NEW），不随业务事务回滚带走。返回记录 id。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Long recordException(String source, String direction, String msgType,
                                String orderNo, Long runId, String errorDetail, String rawContent) {
        XmlExceptionRecord record = new XmlExceptionRecord();
        record.setSource(source);
        record.setDirection(direction);
        record.setMsgType(msgType);
        record.setOrderNo(orderNo);
        record.setRunId(runId);
        record.setErrorDetail(errorDetail);
        record.setRawContent(rawContent);
        record.setStatus(XmlExceptionRecord.STATUS_OPEN);
        exceptionMapper.insert(record);
        return record.getId();
    }

    public List<XmlExceptionRecord> listExceptions(String status, Long runId) {
        String trimmed = status == null ? null : status.trim();
        return exceptionMapper.selectList(new LambdaQueryWrapper<XmlExceptionRecord>()
                .eq(trimmed != null && !trimmed.isEmpty(), XmlExceptionRecord::getStatus, trimmed)
                .eq(runId != null, XmlExceptionRecord::getRunId, runId)
                .orderByDesc(XmlExceptionRecord::getId));
    }

    /** 核销异常：原因必填（服务层兜底），已核销拒绝重复核销。 */
    @Transactional(rollbackFor = Exception.class)
    public XmlExceptionRecord resolveException(Long id, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new BizException("核销原因必填");
        }
        XmlExceptionRecord record = exceptionMapper.selectById(id);
        if (record == null) {
            throw new BizException("异常记录不存在：" + id);
        }
        if (XmlExceptionRecord.STATUS_RESOLVED.equals(record.getStatus())) {
            throw new BizException("异常记录已核销：" + id);
        }
        record.setStatus(XmlExceptionRecord.STATUS_RESOLVED);
        record.setResolveReason(reason.trim());
        record.setResolveOperator(RequestContext.operator());
        record.setResolveTime(LocalDateTime.now());
        exceptionMapper.updateById(record);
        return record;
    }

    // =====================================================================
    // 查询
    // =====================================================================

    public List<TelematicsXmlMessage> listMessages(String orderNo, String direction) {
        String trimmedOrder = orderNo == null ? null : orderNo.trim();
        String trimmedDirection = direction == null ? null : direction.trim();
        return messageMapper.selectList(new LambdaQueryWrapper<TelematicsXmlMessage>()
                .eq(trimmedOrder != null && !trimmedOrder.isEmpty(),
                        TelematicsXmlMessage::getOrderNo, trimmedOrder)
                .eq(trimmedDirection != null && !trimmedDirection.isEmpty(),
                        TelematicsXmlMessage::getDirection, trimmedDirection)
                .orderByDesc(TelematicsXmlMessage::getId));
    }

    public TelematicsXmlMessage requireMessage(Long id) {
        TelematicsXmlMessage message = messageMapper.selectById(id);
        if (message == null) {
            throw new BizException("报文归档记录不存在：" + id);
        }
        return message;
    }

    public List<TelematicsXmlRegression> listRegressions() {
        return regressionMapper.selectList(new LambdaQueryWrapper<TelematicsXmlRegression>()
                .orderByDesc(TelematicsXmlRegression::getId));
    }

    public TelematicsXmlRegression requireRegression(Long id) {
        TelematicsXmlRegression run = regressionMapper.selectById(id);
        if (run == null) {
            throw new BizException("回归批次不存在：" + id);
        }
        return run;
    }

    // =====================================================================
    // 归档内部实现
    // =====================================================================

    /** 报文归档：原文落库 + 按订单号分目录落盘；落库失败尽力清理已写文件。 */
    private TelematicsXmlMessage archiveMessage(String direction, String msgType, String msgId,
                                                String orderNo, String refMsgId, String xml,
                                                String status) {
        String fileName = direction.toLowerCase() + "_" + msgId + ".xml";
        Path path = writeArchiveFile(orderNo, fileName, xml);

        TelematicsXmlMessage record = new TelematicsXmlMessage();
        record.setMsgId(msgId);
        record.setDirection(direction);
        record.setMsgType(msgType);
        record.setOrderNo(orderNo);
        record.setRefMsgId(refMsgId);
        record.setContent(xml);
        record.setContentHash(sha256(xml.getBytes(properties.charsetObj())));
        record.setFilePath(path.toString());
        record.setStatus(status);
        record.setResendCount(0);
        try {
            messageMapper.insert(record);
        } catch (RuntimeException ex) {
            // 落库失败（事务回滚）：清理已写文件，避免无记录孤儿文件
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 尽力清理，清理失败由运维按归档目录巡检
            }
            throw ex;
        }
        return record;
    }

    /** 按订单号分目录写归档文件；目录名与文件名安全化（订单号/平台 msgId 均不可信）。 */
    private Path writeArchiveFile(String orderNo, String fileName, String xml) {
        String dirName = orderNo == null || orderNo.trim().isEmpty()
                ? NO_ORDER_DIR : UNSAFE_DIR_CHARS.matcher(orderNo.trim()).replaceAll("_");
        String safeFileName = UNSAFE_DIR_CHARS.matcher(fileName).replaceAll("_");
        Path path = Paths.get(properties.getXmlDir(), dirName, safeFileName);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, xml.getBytes(properties.charsetObj()));
        } catch (IOException ex) {
            throw new BizException("报文归档落盘失败：" + ex.getMessage());
        }
        return path;
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
