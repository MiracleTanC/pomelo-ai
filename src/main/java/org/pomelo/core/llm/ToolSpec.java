package org.pomelo.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义：描述一个可被 LLM 调用的工具（函数）。
 * <p>
 * 用于原生 function calling：随请求下发给模型，模型据此决定是否发起 tool_call。
 *
 * @param name        工具名（模型在 tool_calls 中引用）
 * @param description 工具描述（帮助模型理解何时调用）
 * @param parameters  参数 JSON Schema（描述参数结构，可为空对象表示无参数）
 */
public record ToolSpec(
        String name,
        String description,
        JsonNode parameters
) {
    /**
     * 无参数工具的便捷构造。
     */
    public static ToolSpec of(String name, String description) {
        return new ToolSpec(name, description, null);
    }
}
