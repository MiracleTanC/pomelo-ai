package org.pomelo.service.rag.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.llm.Tool;
import org.pomelo.core.llm.ToolContext;
import org.pomelo.domain.rag.RetrievedChunk;
import org.pomelo.service.rag.RerankService;
import org.pomelo.service.rag.RetrievalService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具：封装 {@link RetrievalService#retrieve} + {@link RerankService#rerank}。
 * <p>
 * 模型通过原生 function calling 调用 {@code search_kb}，传入 query，
 * 本工具执行混合检索（向量+BM25+RRF）+ LLM 重排，返回精炼的 Top-K 片段文本。
 * kbId 从 {@link ToolContext} 获取（会话级固定），无需模型每次输出。
 * <p>
 * 检索到的 {@link RetrievedChunk} 列表会回写到 {@link ToolContext#attributes()}（key={@link #CHUNKS_KEY}），
 * 供编排器（SelfRagOrchestrator）在 Agent 循环结束后回收，用于生成结构化引用（Citation）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchKbTool implements Tool {

    /** attributes 中存放检索片段列表的 key。 */
    public static final String CHUNKS_KEY = "search_kb_chunks";

    private final RetrievalService retrievalService;
    private final RerankService rerankService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "search_kb";
    }

    @Override
    public String description() {
        return "从当前知识库检索与查询相关的文档片段。"
                + "返回重排后的 Top-K 片段（含文档名、页码、正文）。"
                + "当需要事实、数据或特定文档内容时调用；闲聊/常识类问题无需调用。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "检索查询语句（独立、利于语义匹配的自然语言）");
        ArrayNode required = schema.putArray("required");
        required.add("query");
        return schema;
    }

    @Override
    public String execute(String argumentsJson, ToolContext ctx) {
        // 1. 解析参数
        String query = parseQuery(argumentsJson);
        if (query == null || query.isBlank()) {
            return "错误：缺少必填参数 query。";
        }
        if (ctx.kbId() == null || ctx.kbId().isBlank()) {
            return "错误：未指定知识库（kbId 为空）。";
        }

        // 2. 混合检索
        List<RetrievedChunk> candidates;
        try {
            candidates = retrievalService.retrieve(ctx.kbId(), query);
        } catch (Exception e) {
            log.warn("search_kb 检索失败: {}", e.getMessage());
            return "检索失败: " + e.getMessage();
        }
        if (candidates.isEmpty()) {
            return "未检索到相关片段。";
        }

        // 3. LLM 重排（失败则降级用原顺序）
        List<RetrievedChunk> reranked;
        try {
            reranked = rerankService.rerank(query, candidates);
        } catch (Exception e) {
            log.warn("search_kb 重排失败，使用原检索顺序: {}", e.getMessage());
            reranked = candidates;
        }

        // 4. 回写检索片段到上下文（供编排器回收生成 Citation），按 chunkId 去重累积
        collectChunks(ctx, reranked);

        // 5. 格式化输出（给模型阅读 + 引用）
        return formatChunks(reranked);
    }

    /**
     * 将本次检索的片段去重累积到上下文 attributes（模型可能多次调用 search_kb）。
     * 去重 key 为 chunkId（无 chunkId 时用 docId:chunkIndex）。
     */
    @SuppressWarnings("unchecked")
    private void collectChunks(ToolContext ctx, List<RetrievedChunk> reranked) {
        Map<String, RetrievedChunk> collected = ctx.get(CHUNKS_KEY);
        if (collected == null) {
            collected = new LinkedHashMap<>();
            ctx.put(CHUNKS_KEY, collected);
        }
        for (RetrievedChunk c : reranked) {
            String key = c.chunkId() != null && !c.chunkId().isBlank()
                    ? c.chunkId()
                    : c.docId() + ":" + c.chunkIndex();
            collected.putIfAbsent(key, c);
        }
    }

    /** 从 arguments JSON 中提取 query 字段。 */
    private String parseQuery(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            return node.path("query").asText(null);
        } catch (Exception e) {
            log.warn("解析 search_kb 参数失败: {}", argumentsJson);
            return null;
        }
    }

    /** 格式化检索片段为带序号的文本（供模型引用 [1][2]）。 */
    private String formatChunks(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("检索到 ").append(chunks.size()).append(" 个相关片段：\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] (文档: ")
                    .append(c.docName())
                    .append(", 页: ").append(c.page())
                    .append(") ")
                    .append(c.text())
                    .append("\n---\n");
        }
        return sb.toString();
    }
}
