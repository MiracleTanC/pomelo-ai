package org.pomelo.core.llm;

/**
 * 工具调用：模型在响应中发起的一次工具调用请求。
 * <p>
 * 模型不执行工具，只"提议"调用；实际执行由外部代码完成。
 * 统一 Ollama（arguments 为 JSON 对象）与 OpenAI（arguments 为 JSON 字符串）的差异：
 * 本结构始终以 {@code JSON 字符串} 表示 arguments，便于上层统一解析。
 *
 * @param index     流式累积用的索引（OpenAI 流式分片按 index 拼接）
 * @param id        工具调用 ID（OpenAI 有，用于结果回填；Ollama 可能为 null）
 * @param name      要调用的函数名
 * @param arguments 参数 JSON 字符串（如 {@code {"query":"x","top_k":5}}），可能为 "{}"
 */
public record ToolCall(
        int index,
        String id,
        String name,
        String arguments
) {
}
