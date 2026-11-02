package org.pomelo.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM 调用与结果解析的薄封装，供 Self-RAG 各判断步骤复用。
 * 使用 langchain4j ChatModel.chat(String) 直接获取文本回复。
 * <p>
 * 解析策略：优先按 JSON 结构化解析（模型被要求输出 {"answer":"..."} 格式），
 * 解析失败时回退到关键词匹配，保证鲁棒性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmJudge {

    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public String ask(String prompt) {
        return chatModel.chat(prompt).trim();
    }

    /**
     * 解析 yes/no 类判断。
     * 优先解析 JSON {"answer":"yes"|"no"}，失败则回退到关键词匹配。
     */
    public boolean parseYesNo(String text) {
        if (text == null) {
            return false;
        }
        String answer = extractJsonField(text, "answer");
        if (answer != null) {
            return "yes".equalsIgnoreCase(answer);
        }
        // fallback：关键词匹配（与改造前逻辑一致，保证向后兼容）
        log.debug("JSON 解析失败，回退关键词匹配: {}", text);
        String t = text.toLowerCase();
        return t.contains("yes") || t.contains("是");
    }

    /**
     * 解析 supported/unsupported 判断。
     * 优先解析 JSON {"answer":"supported"|"unsupported"}，失败则回退到关键词匹配。
     */
    public boolean parseSupported(String text) {
        if (text == null) {
            return false;
        }
        String answer = extractJsonField(text, "answer");
        if (answer != null) {
            return "supported".equalsIgnoreCase(answer);
        }
        // fallback：关键词匹配（与改造前逻辑一致，保证向后兼容）
        log.debug("JSON 解析失败，回退关键词匹配: {}", text);
        String t = text.toLowerCase();
        return t.contains("support") && !t.contains("unsupport");
    }

    /**
     * 从模型输出中提取 JSON 字段值。
     * 兼容模型可能输出的额外文字（先尝试整段解析，再尝试提取 {...} 片段）。
     *
     * @param text  模型原始输出
     * @param field 要提取的字段名
     * @return 字段值，解析失败返回 null（触发 fallback）
     */
    private String extractJsonField(String text, String field) {
        // 1. 尝试直接解析整段为 JSON
        try {
            JsonNode node = mapper.readTree(text);
            String val = node.path(field).asText(null);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        } catch (Exception ignored) {
            // 不是纯 JSON，继续尝试提取片段
        }
        // 2. 尝试从文本中提取 {...} 片段（模型可能加了前后文字）
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = mapper.readTree(text.substring(start, end + 1));
                String val = node.path(field).asText(null);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            } catch (Exception ignored) {
                // 片段也无法解析，返回 null 触发 fallback
            }
        }
        return null;
    }
}
