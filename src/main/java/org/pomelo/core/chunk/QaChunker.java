package org.pomelo.core.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.chunk.TextChunk;
import org.pomelo.domain.parser.ParsedBlock;
import org.pomelo.domain.parser.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA 问答对切片：用 LLM 从文档内容生成"问题-答案"对，每个 Q&A 作为一个切片，
 * 答案向量化——检索时问题措辞差异大也能通过语义匹配召回。
 * 成本较高（需多次 LLM 调用），适合 FAQ/客服知识库。
 */
@Slf4j
@Component
public class QaChunker implements ChunkStrategy {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单次 LLM 处理的正文字符数上限 */
    private static final int BATCH_SIZE = 4000;
    private static final int MAX_QA_PER_BATCH = 8;

    public QaChunker(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String name() {
        return "qa";
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument doc, int chunkSize, int overlap) {
        List<TextChunk> result = new ArrayList<>();
        int index = 0;

        // 将所有 block 合并为全文
        StringBuilder fullText = new StringBuilder();
        for (ParsedBlock block : doc.blocks()) {
            fullText.append(block.text()).append("\n");
        }
        String content = fullText.toString();

        // 分批调用 LLM 生成 QA 对
        for (int pos = 0; pos < content.length(); pos += BATCH_SIZE) {
            String batch = content.substring(pos, Math.min(pos + BATCH_SIZE, content.length()));
            List<QaPair> pairs = generateQa(batch);
            for (QaPair pair : pairs) {
                // 切片内容 = "Q: 问题\nA: 答案"
                String chunkText = "Q: " + pair.q + "\nA: " + pair.a;
                result.add(TextChunk.of(chunkText, 0, index++));
            }
            if (result.size() >= MAX_QA_PER_BATCH * 5) break; // 防止无限生成
        }

        log.info("QA 问答对切片完成: {} 切片", result.size());
        return result;
    }

    private List<QaPair> generateQa(String text) {
        String prompt = """
                请从以下文档内容中提取 %d 个关键的"问题-答案"对，以 JSON 数组格式返回。
                每个对象包含 "q" 和 "a" 字段，答案要简洁准确。
                只返回 JSON 数组，不要其他内容。

                文档内容：
                %s
                """.formatted(MAX_QA_PER_BATCH, text);

        try {
            String resp = chatModel.chat(prompt).trim();
            String json = extractJsonArray(resp);
            if (json == null) return List.of();

            List<?> list = objectMapper.readValue(json, List.class);
            List<QaPair> pairs = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String q = String.valueOf(m.get("q"));
                    String a = String.valueOf(m.get("a"));
                    if (!q.isBlank() && !"null".equals(q) && !a.isBlank() && !"null".equals(a)) {
                        pairs.add(new QaPair(q, a));
                    }
                }
            }
            return pairs;
        } catch (Exception e) {
            log.warn("QA 生成失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonArray(String text) {
        if (text == null || text.isBlank()) return null;
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private record QaPair(String q, String a) {
    }
}
