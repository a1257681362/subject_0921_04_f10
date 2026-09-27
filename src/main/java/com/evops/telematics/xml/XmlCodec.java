package com.evops.telematics.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 注解驱动的 XML 报文编解码器（零新增依赖，JDK DOM）。
 *
 * 映射：类上 {@link XmlRoot} 定根元素；字段上 {@link XmlAttribute} 映射根元素属性、
 * {@link XmlElement} 映射子元素；输出元素顺序 = 字段声明顺序。
 *
 * 空值与缺省区分（往返无丢失的关键口径）：
 * - null -> 序列化时属性/元素整体缺省，绝不补 0、false、空串等默认值；
 * - 空串 -> 序列化为自闭合元素 {@code <x/>}；
 * - 解析时属性/元素缺省 -> null（不是默认值），{@code <x/>} -> 空串（String 字段）。
 *
 * 解析安全：拒绝 DOCTYPE（防 XXE），禁用外部实体与 XInclude。
 * 未知元素/属性忽略（向前兼容，对方加字段不炸）；文本内容原样读取不去空白，
 * 保证往返转换无丢失。
 */
public final class XmlCodec {

    /** 时间字段格式（与 telematics-1 文件往来口径一致） */
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private XmlCodec() {}

    // =====================================================================
    // 序列化：对象 -> XML
    // =====================================================================

    /**
     * 对象序列化为 XML 报文（声明 UTF-8）。必填字段为 null 时抛出 {@link XmlValidationException}
     * （全量收集）；文本含 XML 1.0 非法字符时抛出 {@link XmlFormatException}（指明字段与字符）。
     */
    public static String marshal(Object message) {
        return marshal(message, "UTF-8");
    }

    /**
     * 对象序列化为 XML 报文，XML 声明按给定字符集输出（与实际落盘/传输字节口径一致）。
     */
    public static String marshal(Object message, String encoding) {
        Class<?> type = message.getClass();
        XmlRoot root = type.getAnnotation(XmlRoot.class);
        if (root == null) {
            throw new XmlFormatException("报文类缺少 @XmlRoot 注解：" + type.getName());
        }

        List<String> errors = new ArrayList<>();
        StringBuilder attrs = new StringBuilder();
        StringBuilder children = new StringBuilder();
        for (Field field : type.getDeclaredFields()) {
            XmlAttribute attr = field.getAnnotation(XmlAttribute.class);
            if (attr != null) {
                Object value = readField(field, message);
                if (value == null) {
                    if (attr.required()) {
                        errors.add("属性 " + attr.name() + "：必填但值为 null");
                    }
                } else {
                    String text = toText(value);
                    checkXmlChars(text, "属性 " + attr.name());
                    attrs.append(' ').append(attr.name()).append("=\"").append(escapeAttr(text)).append('"');
                }
                continue;
            }
            XmlElement elem = field.getAnnotation(XmlElement.class);
            if (elem != null) {
                Object value = readField(field, message);
                if (value == null) {
                    if (elem.required()) {
                        errors.add("元素 " + elem.name() + "：必填但值为 null");
                    }
                    // null -> 元素整体缺省，不补默认值
                } else {
                    String text = toText(value);
                    checkXmlChars(text, "元素 " + elem.name());
                    children.append('\n').append("  ");
                    if (text.isEmpty()) {
                        children.append('<').append(elem.name()).append("/>");
                    } else {
                        children.append('<').append(elem.name()).append('>')
                                .append(escapeText(text))
                                .append("</").append(elem.name()).append('>');
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new XmlValidationException(errors);
        }

        return "<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>"
                + "\n<" + root.name() + attrs + ">"
                + children
                + "\n</" + root.name() + ">\n";
    }

    // =====================================================================
    // 解析：XML -> 对象
    // =====================================================================

    /**
     * XML 报文解析为对象。语法/结构错误抛 {@link XmlFormatException}（带行列号）；
     * 字段校验错误（必填缺失/类型不符/取值非法）全量收集后抛 {@link XmlValidationException}。
     */
    public static <T> T unmarshal(String xml, Class<T> type) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new XmlFormatException("报文为空");
        }
        XmlRoot root = type.getAnnotation(XmlRoot.class);
        if (root == null) {
            throw new XmlFormatException("报文类缺少 @XmlRoot 注解：" + type.getName());
        }
        // 明确拒绝 DOCTYPE（防 XXE），先于 SAX 报错给出中文明确错误
        if (xml.toUpperCase(java.util.Locale.ROOT).contains("<!DOCTYPE")) {
            throw new XmlFormatException("报文不允许包含 DOCTYPE 声明（安全约束，防外部实体注入）");
        }

        Element rootElement = parseDom(xml);
        if (!root.name().equals(rootElement.getTagName())) {
            throw new XmlFormatException(
                    "根元素应为 " + root.name() + "，实际 " + rootElement.getTagName());
        }

        T message = newInstance(type);
        List<String> errors = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            XmlAttribute attr = field.getAnnotation(XmlAttribute.class);
            if (attr != null) {
                String label = "属性 " + attr.name();
                String text = rootElement.hasAttribute(attr.name())
                        ? rootElement.getAttribute(attr.name()) : null;
                applyValue(message, field, text, attr.required(), null, label, errors);
                continue;
            }
            XmlElement elem = field.getAnnotation(XmlElement.class);
            if (elem != null) {
                String label = "元素 " + elem.name();
                Element child = firstChildElement(rootElement, elem.name());
                if (child == null) {
                    applyValue(message, field, null, elem.required(), elem.allowed(), label, errors);
                } else if (firstElementNode(child) != null) {
                    errors.add(label + "：不应包含子元素");
                } else {
                    applyValue(message, field, child.getTextContent(),
                            elem.required(), elem.allowed(), label, errors);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new XmlValidationException(errors);
        }
        return message;
    }

    // =====================================================================
    // 往返比对：回归验证用，逐映射字段比较两个同型对象
    // =====================================================================

    /**
     * 逐映射字段（属性+元素）比较两个同型报文对象，返回差异描述（空 = 往返无丢失）。
     */
    public static List<String> diffMappedFields(Object original, Object restored) {
        if (original == null || restored == null
                || !original.getClass().equals(restored.getClass())) {
            throw new IllegalArgumentException("往返比对要求两个同型非空对象");
        }
        List<String> diffs = new ArrayList<>();
        for (Field field : original.getClass().getDeclaredFields()) {
            String label = null;
            XmlAttribute attr = field.getAnnotation(XmlAttribute.class);
            if (attr != null) {
                label = "属性 " + attr.name();
            }
            XmlElement elem = field.getAnnotation(XmlElement.class);
            if (elem != null) {
                label = "元素 " + elem.name();
            }
            if (label == null) {
                continue;
            }
            Object before = readField(field, original);
            Object after = readField(field, restored);
            if (!Objects.equals(before, after)) {
                diffs.add(label + "：原值 " + render(before) + "，回读 " + render(after));
            }
        }
        return diffs;
    }

    // ---- 解析内部实现 ----

    private static Element parseDom(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // 纵深防御：即便上层漏检 DOCTYPE，也在解析层禁掉外部实体
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            return doc.getDocumentElement();
        } catch (SAXParseException ex) {
            throw new XmlFormatException("XML 语法错误（第 " + ex.getLineNumber()
                    + " 行第 " + ex.getColumnNumber() + " 列）：" + ex.getMessage());
        } catch (XmlFormatException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new XmlFormatException("XML 解析失败：" + ex.getMessage(), ex);
        }
    }

    /** 字段赋值：text 为 null 表示缺省（-> null，不补默认值）。 */
    private static void applyValue(Object message, Field field, String text, boolean required,
                                   String[] allowed, String label, List<String> errors) {
        if (text == null) {
            if (required) {
                errors.add(label + "：必填但报文中缺失");
            }
            return;   // 缺省 -> null，绝不补默认值
        }
        if (text.isEmpty() && field.getType() != String.class) {
            errors.add(label + "：内容为空，无法转换为 " + field.getType().getSimpleName());
            return;
        }
        Object value;
        try {
            value = convert(text, field.getType());
        } catch (XmlFormatException ex) {
            errors.add(label + "：" + ex.getMessage());
            return;
        }
        if (allowed != null && allowed.length > 0 && value instanceof String) {
            boolean hit = false;
            for (String one : allowed) {
                if (one.equals(value)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                errors.add(label + "：取值非法：" + value + "（仅支持 " + String.join("/", allowed) + "）");
                return;
            }
        }
        writeField(field, message, value);
    }

    private static Object convert(String text, Class<?> type) {
        if (type == String.class) {
            return text;
        }
        if (type == Integer.class) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException ex) {
                throw new XmlFormatException("应为整数：" + text);
            }
        }
        if (type == Long.class) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ex) {
                throw new XmlFormatException("应为整数：" + text);
            }
        }
        if (type == LocalDateTime.class) {
            try {
                return LocalDateTime.parse(text, TIME_FMT);
            } catch (DateTimeParseException ex) {
                throw new XmlFormatException("时间格式应为 yyyy-MM-dd HH:mm:ss：" + text);
            }
        }
        if (type.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = enumValue((Class<? extends Enum>) type, text);
            return value;
        }
        throw new XmlFormatException("不支持的字段类型：" + type.getName());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String text) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException ex) {
            throw new XmlFormatException("取值非法：" + text + "（应为 " + type.getSimpleName() + " 枚举值）");
        }
    }

    // ---- 序列化内部实现 ----

    private static String toText(Object value) {
        if (value instanceof LocalDateTime) {
            return TIME_FMT.format((LocalDateTime) value);
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return String.valueOf(value);
    }

    /** XML 1.0 合法字符校验：#x9 #xA #xD #x20-#xD7FF #xE000-#xFFFD #x10000-#x10FFFF */
    private static void checkXmlChars(String text, String label) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean legal = c == 0x9 || c == 0xA || c == 0xD
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD);
            if (!legal) {
                throw new XmlFormatException(label + "：含 XML 1.0 非法字符 U+"
                        + String.format("%04X", (int) c) + "（如控制字符，需源头清洗后再发）");
            }
        }
    }

    private static String escapeText(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeAttr(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- 反射与 DOM 辅助 ----

    private static Element firstChildElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static Element firstElementNode(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return (Element) children.item(i);
            }
        }
        return null;
    }

    private static Object readField(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("字段读取失败：" + field.getName(), ex);
        }
    }

    private static void writeField(Field field, Object target, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("字段写入失败：" + field.getName(), ex);
        }
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new XmlFormatException("报文类缺少无参构造：" + type.getName(), ex);
        }
    }

    private static String render(Object value) {
        if (value == null) {
            return "<null>";
        }
        if (value instanceof String && ((String) value).isEmpty()) {
            return "<空串>";
        }
        return String.valueOf(value);
    }
}
