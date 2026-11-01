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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 自定义 Ollama ChatModel：直接调用 /api/chat，绕过 langchain4j-ollama 的 OllamaClient。
 * <p>
 * langchain4j 的 OllamaClient 内部会触发 Ollama runner 的 /tokenize 端点，
 * 而 Ollama 0.40.x 的 runner 不支持该端点。本实现直接 POST /api/chat（原生接口），
 * 该接口在 Ollama 0.40.x 上已验证可用。
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
}
