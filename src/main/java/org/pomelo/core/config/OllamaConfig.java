package org.pomelo.core.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Ollama 模型配置：本地 qwen2.5:7b（对话）+ nomic-embed-text（768 维嵌入）。
 */
@Configuration
public class OllamaConfig {

    @Value("${pomelo.ollama.base-url}")
    private String baseUrl;

    @Value("${pomelo.ollama.chat-model}")
    private String chatModel;

    @Value("${pomelo.ollama.embed-model}")
    private String embedModel;

    @Value("${pomelo.ollama.embed-dimension}")
    private int embedDimension;

    @Value("${pomelo.ollama.temperature}")
    private double temperature;

    @Value("${pomelo.ollama.num-predict}")
    private int numPredict;

    @Value("${pomelo.ollama.timeout-seconds}")
    private long timeoutSeconds;

    @Bean
    public ChatModel chatModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(chatModel)
                .temperature(temperature)
                .numPredict(numPredict)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(embedModel)
                .dimensions(embedDimension)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
