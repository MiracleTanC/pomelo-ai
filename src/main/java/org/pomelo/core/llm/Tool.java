package org.pomelo.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一工具接口：每个可被 LLM 原生 function calling 调用的能力实现此接口。
 * <p>
 * 工具只描述"我能做什么 + 参数 schema + 怎么执行"，不关心编排逻辑。
 * 实现类加 {@code @Component} 后由 {@link ToolRegistry} 自动发现注册。
 *
 * @see ToolSpec  请求下发给模型时的工具描述（由 name/description/parameters 派生）
 * @see ToolContext  执行时的会话级上下文（kbId 等，避免每个工具都把这些当参数）
 */
public interface Tool {

    /** 工具名（模型在 tool_calls 中引用，需全局唯一）。 */
    String name();

    /** 工具描述（帮助模型理解何时该调用此工具）。 */
    String description();

    /** 参数 JSON Schema（描述 arguments 结构；无参数时返回 {@code {type:object}}）。 */
    JsonNode parameters();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型给出的参数 JSON 字符串（如 {@code {"query":"..."}}）
     * @param ctx           会话级上下文（kbId / 对话历史 / 原始问题等）
     * @return 执行结果文本（将作为 role=tool 消息回填给模型）
     */
    String execute(String argumentsJson, ToolContext ctx);
}
