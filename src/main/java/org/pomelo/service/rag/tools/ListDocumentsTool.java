package org.pomelo.service.rag.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * 文档清单工具：列出当前知识库下的所有文档（名称、状态、切片数、大小）。
 * <p>
 * 供 Agent 回答"知识库里有哪些文档""上传了哪些文件"等元数据类问题。
 * 这类问题 {@code search_kb}（只检索正文）答不了——文档名/状态不是正文内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListDocumentsTool implements Tool {

    private final DocumentMapper documentMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "list_documents";
    }

    @Override
    public String description() {
        return "列出当前知识库下的所有文档（名称、状态、切片数、文件大小）。"
                + "用于回答'知识库里有哪些文档''上传了哪些文件'等元数据类问题。"
                + "注意：此工具不检索文档正文内容，查正文用 search_kb。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");   // 无参数
        schema.putArray("required");
        return schema;
    }

    @Override
    public String execute(String argumentsJson, ToolContext ctx) {
        if (ctx.kbId() == null || ctx.kbId().isBlank()) {
            return "错误：未指定知识库（kbId 为空）。";
        }
        List<Document> docs;
        try {
            docs = documentMapper.findByKbIdOrderByCreatedAtDesc(ctx.kbId());
        } catch (Exception e) {
            log.warn("list_documents 查询失败: {}", e.getMessage());
            return "查询文档列表失败: " + e.getMessage();
        }
        if (docs.isEmpty()) {
            return "知识库中暂无文档。";
        }
        return formatDocs(docs);
    }

    private String formatDocs(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("知识库共 ").append(docs.size()).append(" 个文档：\n");
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(d.getName())
                    .append(" | 状态: ").append(d.getStatus() != null ? d.getStatus().getLabel() : "未知")
                    .append(" | 切片数: ").append(d.getChunkCount())
                    .append(" | 大小: ").append(formatSize(d.getFileSize()))
                    .append("\n");
        }
        return sb.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
