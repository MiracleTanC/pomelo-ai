package org.pomelo.core.llm;

import java.util.List;

/**
 * LLM 调用结果（统一非流式与流式）。
 * <p>
 * 模型响应有两种互斥形态：
 * <ul>
 *   <li>普通文本回答：{@code content} 非空，{@code toolCalls} 为空</li>
 *   <li>工具调用请求：{@code content} 可能为空，{@code toolCalls} 非空（由上层执行后回填结果继续对话）</li>
 * </ul>
 *
 * @param content   模型生成的文本（流式场景为累积后的完整文本）
 * @param toolCalls 模型发起的工具调用列表（无工具调用时为空列表）
 */
public record LlmResult(
        String content,
        List<ToolCall> toolCalls
) {
    /** 模型是否发起了工具调用。 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 仅有文本的便捷构造。 */
    public static LlmResult text(String content) {
        return new LlmResult(content, List.of());
    }

    /** 仅有工具调用的便捷构造。 */
    public static LlmResult toolCalls(List<ToolCall> toolCalls) {
        return new LlmResult("", toolCalls);
    }
}
