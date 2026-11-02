package org.pomelo.service.rag.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.llm.Tool;
import org.pomelo.core.llm.ToolContext;
import org.pomelo.domain.doc.Document;
import org.pomelo.mapper.DocumentMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档状态工具：查询当前知识库下文档的处理/向量化状态。
 * <p>
 * 供 Agent 回答"我上传的文档处理完了吗""哪些文档还在向量化""哪些失败了"等问题。
 * 按 status 参数过滤（可传 UPLOADED/PARSING/INDEXED/PARSE_FAILED 等状态值）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetDocStatusTool implements Tool {

    private final DocumentMapper documentMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "get_doc_status";
    }

    @Override
    public String description() {
        return "查询当前知识库下文档的处理状态（解析/切片/向量化进度）。"
                + "用于回答'文档处理完了吗''哪些还在处理''哪些失败了'等问题。"
                + "可选传 status 过滤，有效值：UPLOADED/PARSING/PARSED/CHUNKING/CHUNKED/"
                + "VECTORIZING/INDEXED/PARSE_FAILED/CHUNK_FAILED/VECTOR_FAILED。"
                + "不传 status 则返回所有文档及其状态。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.put("description", "按状态过滤，可选；不传则返回所有文档状态");
        schema.putArray("required");
        return schema;
    }

    @Override
    public String execute(String argumentsJson, ToolContext ctx) {
        if (ctx.kbId() == null || ctx.kbId().isBlank()) {
            return "错误：未指定知识库（kbId 为空）。";
        }

        // 无 status → 全量；有 status → 按状态过滤
        List<Document> docs;
        String statusFilter = parseStatus(argumentsJson);
        try {
            if (statusFilter == null) {
                docs = documentMapper.findByKbIdOrderByCreatedAtDesc(ctx.kbId());
            } else {
                docs = documentMapper.findByKbIdAndStatusIn(
                        ctx.kbId(), List.of(parseStatusEnum(statusFilter)));
            }
        } catch (Exception e) {
            log.warn("get_doc_status 查询失败: {}", e.getMessage());
            return "查询文档状态失败: " + e.getMessage();
        }
        if (docs.isEmpty()) {
            return statusFilter == null
                    ? "知识库中暂无文档。"
                    : "没有状态为 " + statusFilter + " 的文档。";
        }
        return formatStatus(docs, statusFilter);
    }

    private String parseStatus(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            String s = node.path("status").asText(null);
            return (s == null || s.isBlank()) ? null : s.toUpperCase();
        } catch (Exception e) {
            log.warn("解析 get_doc_status 参数失败: {}", argumentsJson);
            return null;
        }
    }

    private org.pomelo.common.enums.DocStatus parseStatusEnum(String status) {
        try {
            return org.pomelo.common.enums.DocStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的状态值: " + status
                    + "（有效值见工具描述）");
        }
    }

    private String formatStatus(List<Document> docs, String statusFilter) {
        StringBuilder sb = new StringBuilder();
        sb.append(statusFilter != null
                ? "状态为 " + statusFilter + " 的文档（" + docs.size() + " 个）：\n"
                : "所有文档状态（" + docs.size() + " 个）：\n");
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(d.getName())
                    .append(" | 状态: ").append(d.getStatus() != null ? d.getStatus().getLabel() : "未知")
                    .append(" (").append(d.getStatus() != null ? d.getStatus().name() : "?").append(")")
                    .append(" | 切片: ").append(d.getChunkCount())
                    .append(" | 已向量化: ").append(d.getVectorizedChunks());
            if (d.getErrorMsg() != null && !d.getErrorMsg().isBlank()) {
                sb.append(" | 错误: ").append(d.getErrorMsg());
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
