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
 * Ollama 原生流式聊天客户端（JDK HttpClient）。
 * <p>
 * 直接调用 Ollama /api/chat（stream=true），逐行读取 NDJSON，
 * 解析 message.content 推送给消费者，实现真实 token 级流式输出。
 * 避免依赖 langchain4j 流式 API，降低版本耦合风险。
 */
@Slf4j
@Service
public class OllamaStreamingClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Value("${pomelo.ollama.base-url}")
    private String baseUrl;

    @Value("${pomelo.ollama.chat-model}")
    private String chatModel;

    @Value("${pomelo.ollama.temperature}")
    private double temperature;

    @Value("${pomelo.ollama.num-predict}")
    private int numPredict;

    @Value("${pomelo.ollama.timeout-seconds}")
    private long timeoutSeconds;

    /**
     * 流式对话。messages: [{"role":"system","content":"..."}, {"role":"user","content":"..."}]
     * onToken: 每收到一个 token 片段回调。返回完整文本。
     */
    public String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) throws Exception {
        Map<String, Object> payload = Map.of(
                "model", chatModel,
                "messages", messages,
                "stream", true,
                "options", Map.of(
                        "temperature", temperature,
                        "num_predict", numPredict
                )
        );
        String body = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
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
        // 逐行处理 NDJSON
        try (java.util.stream.Stream<String> lines = resp.body()) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) {
                    return;
                }
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode content = node.path("message").path("content");
                    if (!content.isMissingNode() && !content.asText().isEmpty()) {
                        String token = content.asText();
                        full.append(token);
                        onToken.accept(token);
                    }
                } catch (Exception e) {
                    log.warn("解析流式响应行失败: {}", line);
                }
            });
        }
        return full.toString();
    }
}
