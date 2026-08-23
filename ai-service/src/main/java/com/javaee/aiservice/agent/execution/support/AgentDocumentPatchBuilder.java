package com.javaee.aiservice.agent.execution.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.model.AgentToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentDocumentPatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentDocumentPatchBuilder.class);
    private final ObjectMapper objectMapper;

    public AgentDocumentPatchBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentToolResult documentWrite(Map<String, Object> params, Map<String, Object> context) {
        String documentId = firstNonBlank(asString(params.get("documentId")), asString(context.get("documentId")));
        String content = firstNonBlank(asString(params.get("content")), asString(context.get("answer")),
                asString(context.get("lastAnswer")));
        if (isBlank(content)) {
            return AgentToolResult.error("document-write", "缺少要写回文档的文本内容，请先执行 direct-answer 生成内容");
        }

        Map<String, Object> writePayload = parseFrontendWritePayload(content);
        String payloadContent = firstNonBlank(asString(writePayload.get("content")), content);
        String contentFormat = firstNonBlank(asString(writePayload.get("contentFormat")),
                asString(params.get("contentFormat")), "plain_text");
        return createFrontendWriteResult(
                "document-write", payloadContent,
                normalizeWriteMode(firstNonBlank(asString(writePayload.get("writeMode")), asString(params.get("writeMode")))),
                documentId, null, null,
                firstNonBlank(asString(writePayload.get("changeLog")), asString(params.get("changeLog")),
                        "AI Agent生成前端文档增量内容"),
                firstNonBlank(asString(params.get("selectionText")), asString(context.get("selectedText"))),
                firstNonBlank(asString(writePayload.get("insertAfterText")), asString(writePayload.get("anchorText")),
                        asString(writePayload.get("insertionAnchor")), asString(params.get("insertAfterText")),
                        asString(context.get("insertAfterText"))),
                contentFormat);
    }

    public AgentToolResult textToFile(Map<String, Object> params, Map<String, Object> context, String defaultBucket) {
        String content = firstNonBlank(asString(params.get("content")), asString(context.get("answer")),
                asString(context.get("lastAnswer")));
        if (isBlank(content)) {
            return AgentToolResult.error("text-to-file", "缺少要写入的文本内容，请先执行 direct-answer 生成内容");
        }

        String objectName = asString(params.get("objectName"));
        String bucketName = firstNonBlank(asString(params.get("bucketName")), defaultBucket);
        String contentType = firstNonBlank(asString(params.get("contentType")), "text/plain");
        Map<String, Object> writePayload = parseFrontendWritePayload(content);
        String payloadContent = firstNonBlank(asString(writePayload.get("content")), content);
        return createFrontendWriteResult(
                "text-to-file", payloadContent,
                normalizeWriteMode(firstNonBlank(asString(writePayload.get("writeMode")), asString(params.get("writeMode")))),
                firstNonBlank(asString(params.get("documentId")), asString(context.get("documentId"))),
                bucketName, objectName,
                firstNonBlank(asString(writePayload.get("changeLog")), asString(params.get("changeLog")),
                        "AI Agent生成前端文档增量内容"),
                firstNonBlank(asString(params.get("selectionText")), asString(context.get("selectedText"))),
                firstNonBlank(asString(writePayload.get("insertAfterText")), asString(writePayload.get("anchorText")),
                        asString(writePayload.get("insertionAnchor")), asString(params.get("insertAfterText")),
                        asString(context.get("insertAfterText"))),
                contentType);
    }

    private AgentToolResult createFrontendWriteResult(String toolName, String content, String writeMode,
                                                       String documentId, String bucketName, String objectName,
                                                       String changeLog, String selectionText,
                                                       String insertAfterText, String contentFormat) {
        String normalizedContentFormat = firstNonBlank(contentFormat, "plain_text");
        String finalContent = "html".equalsIgnoreCase(normalizedContentFormat)
                ? content : sanitizeGeneratedDocumentContent(content);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", "frontend-document-write");
        data.put("frontendAction", "apply-document-content");
        data.put("requiresFrontendWrite", true);
        data.put("persisted", false);
        data.put("documentId", firstNonBlank(documentId, "current-editor"));
        data.put("writeMode", writeMode);
        data.put("content", finalContent);
        data.put("contentFormat", normalizedContentFormat);
        data.put("changeLog", firstNonBlank(changeLog, "AI Agent生成前端文档增量内容"));
        data.put("selectionText", selectionText);
        data.put("insertAfterText", sanitizeAnchorText(insertAfterText));
        data.put("contentLength", finalContent.getBytes(StandardCharsets.UTF_8).length);
        data.put("nextStep", "前端已收到待写入内容；用户确认后可使用现有保存功能持久化到MinIO。");
        if (!isBlank(bucketName)) {
            data.put("bucketName", bucketName);
        }
        if (!isBlank(objectName)) {
            data.put("objectName", objectName);
        }
        String target = !isBlank(documentId) ? documentId : firstNonBlank(objectName, "current-editor");
        return AgentToolResult.success(toolName,
                "%s已生成前端写入内容: %s (%s)，尚未保存到MinIO".formatted(toolName, target, writeMode), data);
    }

    private Map<String, Object> parseFrontendWritePayload(String rawContent) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("content", rawContent);
        if (isBlank(rawContent)) {
            return fallback;
        }
        String text = rawContent.trim();
        if (!text.startsWith("{") && !text.startsWith("```")) {
            return fallback;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(stripObjectJson(text), new TypeReference<>() { });
            if (parsed.containsKey("content")) {
                return parsed;
            }
        } catch (Exception e) {
            log.debug("前端写入结构化JSON解析失败，按普通正文处理: {}", e.getMessage());
        }
        return fallback;
    }

    private String sanitizeGeneratedDocumentContent(String content) {
        if (content == null) {
            return "";
        }
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("(?s)^\\s*```[a-zA-Z]*\\s*", "");
        text = text.replaceAll("(?s)\\s*```\\s*$", "");
        text = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        text = text.replaceAll("(?m)^\\s*>\\s?", "");
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        text = text.replaceAll("\\*\\*([^*\\n]+)\\*\\*", "$1");
        text = text.replaceAll("__([^_\\n]+)__", "$1");
        text = text.replaceAll("`([^`\\n]+)`", "$1");
        text = text.replaceAll("\\$([^$\\n]{1,200})\\$", "$1");
        text = text.replace("$", "");
        text = normalizeLatexMarkers(text);
        text = text.replaceAll("(?m)[ \\t]+$", "");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private String sanitizeAnchorText(String text) {
        if (isBlank(text)) {
            return "";
        }
        String anchor = sanitizeGeneratedDocumentContent(text);
        return anchor.length() > 240 ? anchor.substring(0, 240) : anchor;
    }

    private String normalizeLatexMarkers(String text) {
        return text.replace("\\mathbb{C}", "C").replace("\\mathbb{Q}", "Q")
                .replace("\\mathbb{R}", "R").replace("\\mathbb{Z}", "Z").replace("\\mathbb{N}", "N")
                .replace("\\in", "∈").replace("\\sum", "∑").replace("\\cdot", "·")
                .replace("\\times", "×").replace("\\leq", "≤").replace("\\geq", "≥")
                .replace("\\neq", "≠").replace("\\mid", "|").replace("\\(", "")
                .replace("\\)", "").replace("\\[", "").replace("\\]", "");
    }

    private String normalizeWriteMode(String writeMode) {
        String mode = firstNonBlank(writeMode, "append").toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "overwrite", "append", "insert", "replace-selection" -> mode;
            default -> "append";
        };
    }

    private String stripObjectJson(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
