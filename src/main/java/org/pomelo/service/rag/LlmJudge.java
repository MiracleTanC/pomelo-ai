package org.pomelo.service.rag;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LLM 调用与结果解析的薄封装，供 Self-RAG 各判断步骤复用。
 * 使用 langchain4j ChatModel.chat(String) 直接获取文本回复。
 */
@Component
@RequiredArgsConstructor
public class LlmJudge {

    private final ChatModel chatModel;

    public String ask(String prompt) {
        return chatModel.chat(prompt).trim();
    }

    /** 解析 yes/no 类判断。 */
    public boolean parseYesNo(String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("yes") || t.contains("是");
    }

    /** 解析 supported/unsupported 判断。 */
    public boolean parseSupported(String text) {
        if (text == null) {
            return false;
        }
        return text.toLowerCase().contains("support") && !text.toLowerCase().contains("unsupport");
    }
}
