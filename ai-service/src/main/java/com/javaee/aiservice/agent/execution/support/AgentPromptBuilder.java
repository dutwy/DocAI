package com.javaee.aiservice.agent.execution.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.model.AgentPlanStep;
import com.javaee.aiservice.agent.execution.model.AgentToolResult;
import com.javaee.aiservice.agent.execution.tool.AgentToolDefinition;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentPromptBuilder {

    private final ObjectMapper objectMapper;
    private final AgentToolRegistry toolRegistry;

    public AgentPromptBuilder(ObjectMapper objectMapper, AgentToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    public String buildPlannerPrompt(AgentExecutionRequest request, Map<String, Object> context) {
        return """
                你是DocAI的任务规划器。请只输出JSON数组，不要输出解释、Markdown或代码块。
                %s
                只能从工具列表中选择toolName；如不需要工具，使用direct-answer。
                多步任务可以输出多个步骤，但不要超过5步；参数必须来自用户任务或上下文，不确定时先用direct-answer询问澄清。
                对危险操作(file-restore,file-version-switch)必须等待用户确认；file-delete 在前端已确认且 documentId 或 objectName 明确时可执行永久删除，不进入回收站。不要规划文件上传，文件上传必须由用户通过页面或上传接口自行完成。
                document-write 和 text-to-file 只返回前端写入补丁，不会保存MinIO；只有用户明确要求写入当前文档或上下文 frontendDocumentWrite=true 时才使用。

                工具列表:
                %s

                用户任务:
                %s

                上下文:
                %s
                """.formatted(plannerJsonContract(), toolCatalog(), request.getTask(), safeJson(context));
    }

    public String buildFollowUpPlannerPrompt(AgentExecutionRequest request, Map<String, Object> context,
                                              List<AgentPlanStep> previousPlan,
                                              List<AgentToolResult> previousResults,
                                              int iteration) {
        return """
                你是DocAI的Agent执行器，正在进行第%s轮补充规划。请只输出JSON数组，不要输出解释、Markdown或代码块。
                如果任务已经可以回答，请输出空数组 []。
                如果还需要工具，请输出最多3个后续步骤。
                %s
                只能使用工具列表中的toolName；不要重复已经成功执行且参数相同的工具。
                如果后续步骤要消费前面步骤的结果，请使用 ${steps.<id>.data.<key>} 或 ${steps.<id>.observation}，并在 dependsOn 中写入对应 id。

                工具列表:
                %s

                用户任务:
                %s

                已执行计划:
                %s

                工具观察结果:
                %s

                当前上下文:
                %s
                """.formatted(iteration, plannerJsonContract(), toolCatalog(), request.getTask(), safeJson(previousPlan),
                safeJson(previousResults), safeJson(context));
    }

    public String plannerJsonContract() {
        return """
                计划JSON字段契约:
                - 必填字段: id, description, toolName, params, reasoning。
                - 可选字段: dependsOn, successCriteria, retryPolicy, maxRetries, riskLevel。
                - dependsOn 必须引用同一个计划中更早步骤的 id，例如 ["search"]；后续步骤依赖前序工具结果时必须填写。
                - successCriteria 用于声明工具成功判据，可写 contains:关键词、data.<key>、data.<key>=value，多条用分号分隔。
                - retryPolicy 只能使用 none 或 exponential；危险操作使用 none，普通查询/生成可使用 exponential。
                - maxRetries 是整数，普通工具建议 1，危险操作必须 0。
                - 后续步骤需要引用前序结果时，params 里可以使用占位符: ${task}, ${context.<key>}, ${steps.<id>.observation}, ${steps.<id>.data.<key>}。
                - 使用 ${steps.<id>.data.<key>} 时，该步骤必须在 dependsOn 中依赖产生结果的 <id>。
                - RAG 工具的 rerankStrategy 只是候选片段重排序策略，允许 HYBRID/VECTOR/BM25；通常省略或用 HYBRID，不要把它当成业务检索策略。
                """;
    }

    public String frontendDocumentWritePrompt() {
        return """
                请根据下面的当前前端文档内容完成用户任务，输出一个JSON对象，不要输出Markdown、代码块或额外说明。
                JSON格式:
                {"content":"要写入文档的干净正文","writeMode":"insert","insertAfterText":"从原文中复制的一小段锚点文本","changeLog":"本次修改说明","contentFormat":"plain_text"}

                正文格式要求:
                - content 里不要出现 Markdown 符号，例如 **、* 列表符号、# 标题符号、```。
                - 数学表达不要用 $ 包裹，尽量使用普通文本或 Unicode 符号，例如 P、C、Q、R、∈、∑。
                - 不要输出“以下是”“已完成”等聊天回复，只输出可以放进文档的内容。

                插入位置要求:
                - 如果用户要求扩写、补充、续写文档中已有的某一部分，请将 writeMode 设为 insert。
                - insertAfterText 必须从原文中复制一个稳定片段，优先选择该部分最后一句或最后一段；前端会把 content 插入到这段后面。
                - 如果用户选中了文本并要求润色/纠错/替换，请将 writeMode 设为 replace-selection，insertAfterText 留空。
                - 如果用户明确要求覆盖全文，writeMode 才设为 overwrite。
                - 如果无法判断插入位置，insertAfterText 留空，前端会追加到文档末尾。

                用户任务:
                ${task}

                写入模式:
                ${writeMode}

                前端选中文本:
                ${selectedText}

                业务文档原文:
                ${documentContent}
                """;
    }

    private String toolCatalog() {
        StringBuilder tools = new StringBuilder();
        for (AgentToolDefinition tool : toolRegistry.list()) {
            tools.append("- ").append(tool.getName()).append(": ").append(tool.getDescription())
                    .append(" 类别: ").append(tool.getCategory())
                    .append(" 风险等级: ").append(tool.getRiskLevel())
                    .append(" 危险操作: ").append(tool.isDestructive())
                    .append(" 需要用户动作: ").append(tool.isRequiresUserAction())
                    .append(" 参数schema: ").append(safeJson(tool.getParameterSchema())).append("\n");
        }
        tools.append("提示: 如果用户任务缺少必填参数或存在歧义，请优先选择 ask-user 工具向用户澄清，不要凭空填充参数。\n");
        return tools.toString();
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
