package com.javaee.aiservice.agent.execution.support;

import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.model.AgentPlanStep;
import com.javaee.aiservice.agent.execution.model.AgentToolResult;
import com.javaee.aiservice.agent.execution.tool.AgentToolDefinition;
import com.javaee.aiservice.agent.execution.tool.AgentToolParameterDefinition;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentToolParameterResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private final AgentToolRegistry toolRegistry;

    public AgentToolParameterResolver(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public void resolvePlaceholders(AgentPlanStep step, AgentExecutionRequest request, Map<String, Object> context) {
        Map<String, Object> params = step.getParams();
        if (params == null || params.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : new ArrayList<>(params.entrySet())) {
            Object value = entry.getValue();
            if (value instanceof String text && text.contains("${")) {
                params.put(entry.getKey(), renderTemplate(text, request, context));
            }
        }
    }

    public AgentToolResult validateToolParameters(AgentPlanStep step) {
        AgentToolDefinition definition = toolRegistry.get(step.getToolName());
        if (definition == null) {
            return null;
        }

        Map<String, Object> params = step.getParams();
        if ("file-delete".equals(definition.getName())
                && isValueMissing(params.get("documentId"))
                && isValueMissing(params.get("objectName"))) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("toolName", definition.getName());
            data.put("missingParameters", List.of("documentId/objectName"));
            data.put("parameterSchema", definition.getParameterSchema());
            data.put("question", "工具 file-delete 需要提供前端documentId或MinIO对象名称objectName");
            data.put("nextStep", "请从前端上下文传入 documentId，或直接提供 bucketName/objectName 后重试。");
            data.put("interactionType", "user_input");
            data.put("resumeMode", "continue_trace");
            data.put("resumeEndpointTemplate", "/api/ai/agent/tasks/{traceId}/continue");
            return AgentToolResult.actionRequired(definition.getName(),
                    "请提供前端documentId或MinIO对象名称objectName", data);
        }

        List<String> missing = new ArrayList<>();
        Map<String, String> invalid = new LinkedHashMap<>();
        for (AgentToolParameterDefinition parameter : definition.getParameterSchema().values()) {
            Object value = params.get(parameter.getName());
            if (parameter.isRequired() && isValueMissing(value)) {
                missing.add(parameter.getName());
                continue;
            }
            if (isValueMissing(value)) {
                continue;
            }
            if (!isTypeCompatible(value, parameter.getType())) {
                invalid.put(parameter.getName(), "期望类型: " + parameter.getType());
                continue;
            }
            if (parameter.getAllowedValues() != null && !parameter.getAllowedValues().isEmpty()
                    && !parameter.getAllowedValues().contains(value)
                    && !parameter.getAllowedValues().contains(value.toString())) {
                invalid.put(parameter.getName(), "仅允许: " + parameter.getAllowedValues());
                continue;
            }
            if ("integer".equals(parameter.getType())) {
                long numeric;
                try {
                    numeric = Long.parseLong(value.toString());
                } catch (NumberFormatException ignore) {
                    invalid.put(parameter.getName(), "期望类型: integer");
                    continue;
                }
                if (parameter.getMinValue() != null && numeric < parameter.getMinValue().longValue()) {
                    invalid.put(parameter.getName(), "最小值: " + parameter.getMinValue());
                    continue;
                }
                if (parameter.getMaxValue() != null && numeric > parameter.getMaxValue().longValue()) {
                    invalid.put(parameter.getName(), "最大值: " + parameter.getMaxValue());
                    continue;
                }
            }
            if (parameter.getPattern() != null && !value.toString().matches(parameter.getPattern())) {
                invalid.put(parameter.getName(), "格式不匹配: " + parameter.getPattern());
            }
        }

        if (missing.isEmpty() && invalid.isEmpty()) {
            return null;
        }
        StringBuilder question = new StringBuilder("工具 ").append(definition.getName()).append(" 缺少必要信息: ");
        if (!missing.isEmpty()) {
            question.append("缺失参数 ").append(missing);
        }
        if (!invalid.isEmpty()) {
            if (!missing.isEmpty()) {
                question.append("; ");
            }
            question.append("非法参数 ").append(invalid.keySet());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", definition.getName());
        data.put("missingParameters", missing);
        data.put("invalidParameters", invalid);
        data.put("parameterSchema", definition.getParameterSchema());
        data.put("question", question.toString());
        data.put("nextStep", "请补齐缺失或类型不正确的参数后，将参数放入 context 或工具 params 中重试。");
        data.put("interactionType", "user_input");
        data.put("resumeMode", "continue_trace");
        data.put("resumeEndpointTemplate", "/api/ai/agent/tasks/{traceId}/continue");
        return AgentToolResult.actionRequired(definition.getName(), question.toString(), data);
    }

    public boolean isValueMissing(Object value) {
        return value == null || value.toString().trim().isEmpty() || "null".equalsIgnoreCase(value.toString().trim());
    }

    private String renderTemplate(String text, AgentExecutionRequest request, Map<String, Object> context) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            Object resolved = resolveExpression(matcher.group(1).trim(), request, context);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolved == null ? "" : resolved.toString()));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    @SuppressWarnings("unchecked")
    private Object resolveExpression(String expression, AgentExecutionRequest request, Map<String, Object> context) {
        if ("task".equals(expression)) {
            return request.getTask();
        }
        String[] parts = expression.split("\\.");
        if (parts.length < 2) {
            return context.get(expression);
        }
        if ("context".equals(parts[0])) {
            return drillInto(context, parts, 1);
        }
        if ("steps".equals(parts[0])) {
            Object steps = context.get("__stepResults__");
            if (!(steps instanceof Map)) {
                return null;
            }
            Object stepData = ((Map<String, Object>) steps).get(parts[1]);
            if (parts.length == 2) {
                return stepData;
            }
            if (stepData instanceof Map<?, ?> map) {
                return drillInto((Map<String, Object>) map, parts, 2);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object drillInto(Map<String, Object> source, String[] parts, int from) {
        Object current = source;
        for (int i = from; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(parts[i]);
        }
        return current;
    }

    private boolean isTypeCompatible(Object value, String type) {
        if (value == null) {
            return false;
        }
        String normalizedType = type == null || type.isBlank() ? "string" : type;
        return switch (normalizedType) {
            case "integer" -> value instanceof Number || value.toString().matches("^-?\\d+$");
            case "boolean" -> value instanceof Boolean
                    || "true".equalsIgnoreCase(value.toString())
                    || "false".equalsIgnoreCase(value.toString());
            default -> true;
        };
    }
}
