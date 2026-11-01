package org.pomelo.core.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.ollama.OllamaNativeChatModel;
import org.pomelo.core.ollama.OllamaNativeEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 模型配置：支持 Ollama 本地模型 和 OpenAI 兼容在线模型（DeepSeek / 硅基流动等）。
 * <p>
 * langchain4j 1.23.0 的 OllamaEmbeddingModel/OllamaChatModel 不再调用 /tokenize 端点，
 * 可直接使用官方实现，兼容 Ollama 0.40.x。
 */
@Slf4j
@Configuration
public class LlmConfig {

    @Value("${pomelo.llm.provider}")
    private String provider;

    @Value("${pomelo.llm.temperature}")
    private double temperature;

    @Value("${pomelo.llm.max-tokens}")
    private int maxTokens;

    @Value("${pomelo.llm.timeout-seconds}")
    private long timeoutSeconds;

    // ---- Ollama ----
    @Value("${pomelo.llm.ollama.base-url:}")
    private String ollamaBaseUrl;

    @Value("${pomelo.llm.ollama.chat-model:}")
    private String ollamaChatModel;

    @Value("${pomelo.llm.ollama.embed-model:}")
    private String ollamaEmbedModel;

    @Value("${pomelo.llm.ollama.embed-dimension:768}")
    private int ollamaEmbedDimension;

    // ---- OpenAI 兼容 ----
    @Value("${pomelo.llm.chat.base-url:}")
    private String chatBaseUrl;

    @Value("${pomelo.llm.chat.api-key:}")
    private String chatApiKey;

    @Value("${pomelo.llm.chat.model:}")
    private String chatModel;

    @Value("${pomelo.llm.embed.base-url:}")
    private String embedBaseUrl;

    @Value("${pomelo.llm.embed.api-key:}")
    private String embedApiKey;

    @Value("${pomelo.llm.embed.model:}")
    private String embedModel;

    @Value("${pomelo.llm.embed.dimension:1024}")
    private int embedDimension;

    @Bean
    public ChatModel chatModel() {
        if ("ollama".equalsIgnoreCase(provider)) {
            // 直接调 Ollama 原生 /api/chat，绕过 langchain4j-ollama 的 /tokenize 调用
            log.info("ChatModel: Ollama native {} @ {}/api/chat", ollamaChatModel, ollamaBaseUrl);
            return new OllamaNativeChatModel(ollamaBaseUrl, ollamaChatModel, temperature, maxTokens,
                    Duration.ofSeconds(timeoutSeconds));
        }
        log.info("ChatModel: {} @ {}", chatModel, chatBaseUrl);
        return OpenAiChatModel.builder()
                .baseUrl(chatBaseUrl)
                .apiKey(chatApiKey)
                .modelName(chatModel)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        if ("ollama".equalsIgnoreCase(provider)) {
            // 直接调 Ollama 原生 /api/embed，绕过 langchain4j-ollama 的 /tokenize 调用
            log.info("EmbeddingModel: Ollama native {} ({}d) @ {}/api/embed", ollamaEmbedModel, ollamaEmbedDimension, ollamaBaseUrl);
            return new OllamaNativeEmbeddingModel(ollamaBaseUrl, ollamaEmbedModel, ollamaEmbedDimension,
                    Duration.ofSeconds(timeoutSeconds));
        }
        log.info("EmbeddingModel: {} ({}d) @ {}", embedModel, embedDimension, embedBaseUrl);
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embedBaseUrl)
                .apiKey(embedApiKey)
                .modelName(embedModel)
                .dimensions(embedDimension)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
