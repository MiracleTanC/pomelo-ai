package org.pomelo.core.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
     * 流式对话。messages: [{"role":"system","content":"..."}, {"role":"user","content":"..."}]
     * onToken: 每收到一个 token 片段回调。返回完整文本。
     */
    public String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) throws Exception {
        return isOllama() ? streamChatOllama(messages, onToken) : streamChatOpenAi(messages, onToken);
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
}
