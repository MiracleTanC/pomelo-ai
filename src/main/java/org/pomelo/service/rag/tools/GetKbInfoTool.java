package org.pomelo.service.rag.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.llm.Tool;
import org.pomelo.core.llm.ToolContext;
import org.pomelo.domain.kb.KnowledgeBase;
import org.pomelo.mapper.DocumentMapper;
import org.pomelo.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Component;

/**
 * 知识库信息工具：返回当前知识库的元信息（名称、描述、文档数、切片数、检索参数）。
 * <p>
 * 供 Agent 回答"这个库多大""Top-K 是多少""阈值是多少"等关于知识库配置本身的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetKbInfoTool implements Tool {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "get_kb_info";
    }

    @Override
    public String description() {
        return "查询当前知识库的元信息（名称、描述、文档数、切片数、Top-K、相似度阈值等）。"
                + "用于回答'这个知识库多大''检索阈值是多少''Top-K 配置'等关于知识库本身的问题。";
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
        KnowledgeBase kb;
        try {
            kb = kbMapper.findById(ctx.kbId());
        } catch (Exception e) {
            log.warn("get_kb_info 查询失败: {}", e.getMessage());
            return "查询知识库信息失败: " + e.getMessage();
        }
        if (kb == null) {
            return "知识库不存在: " + ctx.kbId();
        }
        int docCount = documentMapper.countByKbId(ctx.kbId());
        int chunkCount = documentMapper.sumChunkCountByKbId(ctx.kbId());
        return format(kb, docCount, chunkCount);
    }

    private String format(KnowledgeBase kb, int docCount, int chunkCount) {
        return """
                知识库信息：
                - 名称：%s
                - 描述：%s
                - 文档数：%d
                - 切片数：%d
                - 检索 Top-K：%d
                - 重排 Top-K：%d
                - 相似度阈值：%.2f
                - 切片大小：%d 字符（重叠 %d）
                - 切片策略：%s
                - 自动向量化：%s
                """.formatted(
                kb.getName(),
                kb.getDescription() == null ? "(无)" : kb.getDescription(),
                docCount,
                chunkCount,
                kb.getTopK(),
                kb.getRerankTopK(),
                kb.getSimilarityThreshold(),
                kb.getChunkSize(),
                kb.getChunkOverlap(),
                kb.getChunkStrategy(),
                kb.isAutoVectorize() ? "是" : "否");
    }
}
