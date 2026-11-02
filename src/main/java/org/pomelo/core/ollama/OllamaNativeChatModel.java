package org.pomelo.core.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.llm.LlmResult;
import org.pomelo.core.llm.ToolCall;
import org.pomelo.core.llm.ToolSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自定义 Ollama ChatModel：直接调用 /api/chat，绕过 langchain4j-ollama 的 OllamaClient。
 * <p>
 * langchain4j 的 OllamaClient 内部会触发 Ollama runner 的 /tokenize 端点，
 * 而 Ollama 0.40.x 的 runner 不支持该端点。本实现直接 POST /api/chat（原生接口），
 * 该接口在 Ollama 0.40.x 上已验证可用。
 * <p>
 * 除 {@link #doChat(ChatRequest)}（ChatModel 契约，纯文本，供 LlmJudge 等使用）外，
 * 另提供 {@link #chatWithTools(List, List)} 支持原生 function calling：
 * 请求体带 tools 数组，响应解析 message.tool_calls。
 */
@Slf4j
public class OllamaNativeChatModel implements ChatModel {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String baseUrl;
    private final String modelName;
    private final double temperature;
    private final int numPredict;
    private final Duration timeout;

    public OllamaNativeChatModel(String baseUrl, String modelName, double temperature, int numPredict, Duration timeout) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.temperature = temperature;
        this.numPredict = numPredict;
        this.timeout = timeout;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        try {
            List<ChatMessage> messages = request.messages();

            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelName);
            body.put("stream", false);

            ArrayNode msgArr = body.putArray("messages");
            for (ChatMessage msg : messages) {
                ObjectNode m = msgArr.addObject();
                if (msg instanceof SystemMessage sm) {
                    m.put("role", "system");
                    m.put("content", sm.text());
                } else if (msg instanceof UserMessage um) {
                    m.put("role", "user");
                    m.put("content", um.singleText());
                } else if (msg instanceof AiMessage am) {
                    m.put("role", "assistant");
                    m.put("content", am.text());
                }
            }

            ObjectNode options = body.putObject("options");
            options.put("temperature", temperature);
            options.put("num_predict", numPredict);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Ollama chat 失败 HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("message").path("content").asText("");
            return ChatResponse.builder().aiMessage(AiMessage.from(content)).build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Ollama chat 失败", e);
        }
    }

    /**
     * 带 tools 的非流式对话（原生 function calling）。
     * <p>
     * 请求体附加 tools 数组；响应中解析 message.tool_calls。
     * Ollama 的 tool_calls.function.arguments 为 JSON 对象，统一转为 JSON 字符串。
     *
     * @param messages 消息列表，每个 Map 至少含 {@code role} 与 {@code content}；
     *                 assistant 的 tool_call 消息可含 {@code tool_calls} 数组；
     *                 tool 消息 {@code role="tool"} 含 {@code content}（结果文本）
     * @param tools    可调用工具定义；为空时退化为纯文本对话
     * @return {@link LlmResult}，含文本与/或工具调用
     */
    public LlmResult chatWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", modelName);
            body.put("stream", false);

            ArrayNode msgArr = body.putArray("messages");
            for (Map<String, Object> msg : messages) {
                msgArr.add(mapper.valueToTree(msg));
            }

            // 附加 tools（OpenAI/Ollama 统一格式：[{type:function, function:{name,description,parameters}}]）
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsArr = body.putArray("tools");
                for (ToolSpec tool : tools) {
                    ObjectNode t = toolsArr.addObject();
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tool.name());
                    if (tool.description() != null) {
                        fn.put("description", tool.description());
                    }
                    if (tool.parameters() != null) {
                        fn.set("parameters", tool.parameters());
                    } else {
                        fn.putObject("parameters").put("type", "object");
                    }
                }
            }

            ObjectNode options = body.putObject("options");
            options.put("temperature", temperature);
            options.put("num_predict", numPredict);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Ollama chat 失败 HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode message = root.path("message");
            String content = message.path("content").asText("");

            // 解析 tool_calls（Ollama：arguments 为 JSON 对象）
            List<ToolCall> toolCalls = new ArrayList<>();
            JsonNode tcArr = message.path("tool_calls");
            if (tcArr.isArray()) {
                for (int i = 0; i < tcArr.size(); i++) {
                    JsonNode tc = tcArr.get(i);
                    JsonNode fn = tc.path("function");
                    String name = fn.path("name").asText("");
                    JsonNode argsNode = fn.path("arguments");
                    String args = argsNode.isObject() || argsNode.isArray()
                            ? mapper.writeValueAsString(argsNode)
                            : argsNode.asText("{}");
                    toolCalls.add(new ToolCall(i, tc.path("id").asText(null), name, args));
                }
            }
            return new LlmResult(content, toolCalls);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Ollama chat(tools) 失败", e);
        }
    }
}
