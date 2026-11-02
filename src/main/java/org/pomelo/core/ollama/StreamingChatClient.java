package org.pomelo.core.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.llm.LlmResult;
import org.pomelo.core.llm.ToolCall;
import org.pomelo.core.llm.ToolSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 流式聊天客户端（JDK HttpClient）。
 * <p>
 * 支持两种后端：
 * <ul>
 *   <li><b>ollama</b>：POST /api/chat（NDJSON 流）</li>
 *   <li><b>openai-compatible</b>：POST /chat/completions（SSE data: 流）</li>
 * </ul>
 * 逐 token 推送给消费者，实现真实流式输出。避免依赖 langchain4j 流式 API。
 * <p>
 * 除纯文本流式（{@link #streamChat(List, Consumer)}）外，另提供
 * {@link #streamChat(List, List, Consumer)} 支持原生 function calling：
 * 请求体带 tools，响应增量解析并拼接 tool_calls，返回 {@link LlmResult}。
 */
@Slf4j
@Service
public class StreamingChatClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Value("${pomelo.llm.provider}")
    private String provider;

    // 通用参数
    @Value("${pomelo.llm.temperature}")
    private double temperature;

    @Value("${pomelo.llm.max-tokens}")
    private int maxTokens;

    @Value("${pomelo.llm.timeout-seconds}")
    private long timeoutSeconds;

    // Ollama
    @Value("${pomelo.llm.ollama.base-url:}")
    private String ollamaBaseUrl;

    @Value("${pomelo.llm.ollama.chat-model:}")
    private String ollamaChatModel;

    // OpenAI 兼容（DeepSeek 等）
    @Value("${pomelo.llm.chat.base-url:}")
    private String chatBaseUrl;

    @Value("${pomelo.llm.chat.api-key:}")
    private String chatApiKey;

    @Value("${pomelo.llm.chat.model:}")
    private String chatModel;

    private boolean isOllama() {
        return "ollama".equalsIgnoreCase(provider);
    }

    /**
     * 流式对话（纯文本，无工具）。messages: [{"role":"system","content":"..."}, {"role":"user","content":"..."}]
     * onToken: 每收到一个 token 片段回调。返回完整文本。
     */
    public String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) throws Exception {
        return isOllama() ? streamChatOllama(messages, onToken) : streamChatOpenAi(messages, onToken);
    }

    /**
     * 带 tools 的流式对话（原生 function calling）。
     * <p>
     * 请求体附加 tools；响应中增量解析并拼接 tool_calls（OpenAI 按 index 分片累积 arguments）。
     * 返回 {@link LlmResult}，含累积后的完整文本与工具调用列表。
     *
     * @param messages 消息列表（支持 assistant tool_calls、tool 结果等任意结构）
     * @param tools    可调用工具定义；为空时退化为纯文本流式
     * @param onToken  文本 token 回调（工具调用片段不回调）
     */
    public LlmResult streamChat(List<Map<String, Object>> messages, List<ToolSpec> tools,
                                Consumer<String> onToken) throws Exception {
        return isOllama()
                ? streamChatOllamaWithTools(messages, tools, onToken)
                : streamChatOpenAiWithTools(messages, tools, onToken);
    }

    // ==================== Ollama 流式（NDJSON） ====================

    private String streamChatOllama(List<Map<String, String>> messages, Consumer<String> onToken) throws Exception {
        Map<String, Object> payload = Map.of(
                "model", ollamaChatModel,
                "messages", messages,
                "stream", true,
                "options", Map.of(
                        "temperature", temperature,
                        "num_predict", maxTokens
                )
        );
        String body = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.util.stream.Stream<String>> resp = httpClient.send(
                request, HttpResponse.BodyHandlers.ofLines());

        if (resp.statusCode() != 200) {
            String err = resp.body().reduce("", (a, b) -> a + b);
            throw new RuntimeException("Ollama 流式请求失败 HTTP " + resp.statusCode() + ": " + err);
        }

        StringBuilder full = new StringBuilder();
        try (java.util.stream.Stream<String> lines = resp.body()) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) return;
                try {
                    JsonNode node = mapper.readTree(line);
                    String token = node.path("message").path("content").asText("");
                    if (!token.isEmpty()) {
                        full.append(token);
                        onToken.accept(token);
                    }
                } catch (Exception e) {
                    log.warn("解析 Ollama 流式行失败: {}", line);
                }
            });
        }
        return full.toString();
    }

    private LlmResult streamChatOllamaWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools,
                                                Consumer<String> onToken) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", ollamaChatModel);
        payload.put("stream", true);
        payload.set("messages", mapper.valueToTree(messages));
        ObjectNode options = payload.putObject("options");
        options.put("temperature", temperature);
        options.put("num_predict", maxTokens);
        applyTools(payload, tools);

        String body = mapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.util.stream.Stream<String>> resp = httpClient.send(
                request, HttpResponse.BodyHandlers.ofLines());

        if (resp.statusCode() != 200) {
            String err = resp.body().reduce("", (a, b) -> a + b);
            throw new RuntimeException("Ollama 流式请求失败 HTTP " + resp.statusCode() + ": " + err);
        }

        StringBuilder full = new StringBuilder();
        // 按 index 累积 tool_calls（Ollama 通常整块返回，累积保险）
        Map<Integer, ToolCallAccumulator> acc = new LinkedHashMap<>();
        try (java.util.stream.Stream<String> lines = resp.body()) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) return;
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode message = node.path("message");
                    String token = message.path("content").asText("");
                    if (!token.isEmpty()) {
                        full.append(token);
                        onToken.accept(token);
                    }
                    // Ollama：tool_calls.arguments 为 JSON 对象
                    accumulateToolCalls(acc, message.path("tool_calls"), false);
                } catch (Exception e) {
                    log.warn("解析 Ollama 流式行失败: {}", line);
                }
            });
        }
        return new LlmResult(full.toString(), toToolCalls(acc));
    }

    // ==================== OpenAI 兼容流式（SSE） ====================

    private String streamChatOpenAi(List<Map<String, String>> messages, Consumer<String> onToken) throws Exception {
        Map<String, Object> payload = Map.of(
                "model", chatModel,
                "messages", messages,
                "stream", true,
                "temperature", temperature,
                "max_tokens", maxTokens
        );
        String body = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatBaseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + chatApiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.util.stream.Stream<String>> resp = httpClient.send(
                request, HttpResponse.BodyHandlers.ofLines());

        if (resp.statusCode() != 200) {
            String err = resp.body().reduce("", (a, b) -> a + b);
            throw new RuntimeException("Chat API 流式请求失败 HTTP " + resp.statusCode() + ": " + err);
        }

        StringBuilder full = new StringBuilder();
        try (java.util.stream.Stream<String> lines = resp.body()) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) return;
                // SSE 格式：data: {...}
                if (!line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) return;
                try {
                    JsonNode node = mapper.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                    String token = delta.asText("");
                    if (!token.isEmpty()) {
                        full.append(token);
                        onToken.accept(token);
                    }
                } catch (Exception e) {
                    log.debug("解析 SSE 行失败: {}", data);
                }
            });
        }
        return full.toString();
    }

    private LlmResult streamChatOpenAiWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools,
                                                Consumer<String> onToken) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", chatModel);
        payload.put("stream", true);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.set("messages", mapper.valueToTree(messages));
        applyTools(payload, tools);

        String body = mapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatBaseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + chatApiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.util.stream.Stream<String>> resp = httpClient.send(
                request, HttpResponse.BodyHandlers.ofLines());

        if (resp.statusCode() != 200) {
            String err = resp.body().reduce("", (a, b) -> a + b);
            throw new RuntimeException("Chat API 流式请求失败 HTTP " + resp.statusCode() + ": " + err);
        }

        StringBuilder full = new StringBuilder();
        // 按 index 累积 tool_calls（OpenAI：arguments 按 index 分片，为 JSON 字符串）
        Map<Integer, ToolCallAccumulator> acc = new LinkedHashMap<>();
        try (java.util.stream.Stream<String> lines = resp.body()) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) return;
                if (!line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) return;
                try {
                    JsonNode node = mapper.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta");
                    String token = delta.path("content").asText("");
                    if (!token.isEmpty()) {
                        full.append(token);
                        onToken.accept(token);
                    }
                    // OpenAI：tool_calls 在 delta 中，按 index 累积
                    accumulateToolCalls(acc, delta.path("tool_calls"), true);
                } catch (Exception e) {
                    log.debug("解析 SSE 行失败: {}", data);
                }
            });
        }
        return new LlmResult(full.toString(), toToolCalls(acc));
    }

    // ==================== tools 请求构建 / 响应累积公共逻辑 ====================

    /** 将 ToolSpec 列表序列化为统一 tools 数组（OpenAI/Ollama 同格式）追加到请求体。 */
    private void applyTools(ObjectNode payload, List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        ArrayNode toolsArr = payload.putArray("tools");
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

    /**
     * 增量累积 tool_calls（流式分片按 index 归并）。
     *
     * @param acc              累积器（index → accumulator）
     * @param toolCallsNode    本帧的 tool_calls 数组节点
     * @param argsIsString     true=OpenAI（arguments 为 JSON 字符串，直接拼接）；
     *                         false=Ollama（arguments 为 JSON 对象，整体覆盖）
     */
    private void accumulateToolCalls(Map<Integer, ToolCallAccumulator> acc, JsonNode toolCallsNode, boolean argsIsString) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }
        for (JsonNode tc : toolCallsNode) {
            int idx = tc.path("index").asInt(0);
            ToolCallAccumulator a = acc.computeIfAbsent(idx, k -> new ToolCallAccumulator());
            JsonNode fn = tc.path("function");
            String name = fn.path("name").asText("");
            if (!name.isEmpty()) {
                a.name = name;
            }
            if (tc.has("id")) {
                a.id = tc.path("id").asText(null);
            }
            JsonNode argsNode = fn.path("arguments");
            if (argsIsString) {
                // OpenAI：arguments 为字符串分片，逐片拼接
                String frag = argsNode.asText("");
                if (!frag.isEmpty()) {
                    a.args.append(frag);
                }
            } else {
                // Ollama：arguments 为 JSON 对象，整体覆盖（一般整块返回）
                if (argsNode.isObject() || argsNode.isArray()) {
                    try {
                        a.args.setLength(0);
                        a.args.append(mapper.writeValueAsString(argsNode));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /** 将累积器转为最终的 ToolCall 列表（按 index 升序）。 */
    private List<ToolCall> toToolCalls(Map<Integer, ToolCallAccumulator> acc) {
        List<ToolCall> result = new ArrayList<>();
        for (ToolCallAccumulator a : acc.values()) {
            String args = a.args.toString();
            if (args.isBlank()) {
                args = "{}";
            }
            result.add(new ToolCall(a.index, a.id, a.name, args));
        }
        return result;
    }

    /** 流式 tool_calls 增量累积器。 */
    private static class ToolCallAccumulator {
        int index;
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }
}
