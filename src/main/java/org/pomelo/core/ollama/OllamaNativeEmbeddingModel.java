package org.pomelo.core.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.request.EmbeddingInput;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 自定义 Ollama EmbeddingModel：直接调用 /api/embed（单字符串模式），绕过 /tokenize。
 * <p>
 * Ollama 0.40.x 的 /api/embed 和 /v1/embeddings 在数组（批量）模式下都会
 * 触发 runner 的 /tokenize 端点（0.40.x runner 不支持 → 连接拒绝）。
 * 只有 input 为字符串（非数组）时才不触发 /tokenize。
 * <p>
 * 因此本实现逐条发送（input 为字符串），但用线程池并发执行（8 并发），
 * 兼顾可靠性和速度。70 条切片约 9 秒完成。
 */
@Slf4j
public class OllamaNativeEmbeddingModel extends DimensionAwareEmbeddingModel {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String baseUrl;
    private final String modelName;
    private final int dimension;
    private final Duration timeout;

    /** 并发嵌入请求数（Ollama 本地 GPU 通常能处理并发 embed 请求） */
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public OllamaNativeEmbeddingModel(String baseUrl, String modelName, int dimension, Duration timeout) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimension = dimension;
        this.timeout = timeout;
    }

    @Override
    public EmbeddingResponse doEmbed(EmbeddingRequest request) {
        try {
            List<String> inputs = request.inputs().stream()
                    .map(EmbeddingInput::text)
                    .toList();

            log.info(">>> [OLLAMA-EMBED] doEmbed 开始: {} 条文本, model={}, 并发=8, 端点={}/api/embed (字符串模式)",
                    inputs.size(), modelName, baseUrl);
            long t0 = System.currentTimeMillis();

            // 逐条并发发送（input 用字符串，不用数组）
            // 数组格式会触发 Ollama 0.40.x runner 的 /tokenize，字符串格式不会
            List<Callable<Embedding>> tasks = new ArrayList<>();
            for (String input : inputs) {
                tasks.add(() -> embedSingle(input));
            }

            // 提交所有任务
            List<Future<Embedding>> futures = pool.invokeAll(tasks);

            // 按顺序收集结果
            List<Embedding> result = new ArrayList<>(inputs.size());
            for (Future<Embedding> f : futures) {
                result.add(f.get());
            }

            long elapsed = System.currentTimeMillis() - t0;
            log.info("<<< [OLLAMA-EMBED] doEmbed 完成: {} 个向量, dim={}, 耗时 {}ms (avg {}ms/条)",
                    result.size(),
                    result.isEmpty() ? 0 : result.get(0).vector().length,
                    elapsed,
                    result.isEmpty() ? 0 : elapsed / result.size());

            return EmbeddingResponse.builder()
                    .embeddings(result)
                    .modelName(modelName)
                    .build();
        } catch (RuntimeException e) {
            log.error("!!! [OLLAMA-EMBED] doEmbed 失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("!!! [OLLAMA-EMBED] doEmbed 失败", e);
            throw new RuntimeException("Ollama embed 失败", e);
        }
    }

    /**
     * 单条文本嵌入：POST /api/embed，input 为字符串。
     */
    private Embedding embedSingle(String input) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);
        body.put("input", input);  // 字符串，不是数组

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - t0;

        if (resp.statusCode() != 200) {
            log.error("!!! [OLLAMA-EMBED] embedSingle 失败 HTTP {}: {} (耗时 {}ms, input={}字符)",
                    resp.statusCode(), resp.body(), elapsed,
                    input.length() > 50 ? input.substring(0, 50) + "..." : input.length());
            throw new RuntimeException("Ollama embed 失败 HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode embeddings = root.path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) {
            log.error("!!! [OLLAMA-EMBED] embedSingle 返回无 embeddings: {} (耗时 {}ms)", resp.body(), elapsed);
            throw new RuntimeException("Ollama embed 返回无 embeddings: " + resp.body());
        }

        JsonNode vec = embeddings.get(0);
        float[] vector = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            vector[i] = (float) vec.get(i).asDouble();
        }

        log.debug("[OLLAMA-EMBED] embedSingle OK: dim={}, 耗时 {}ms, input={}字符",
                vector.length, elapsed, input.length());
        return Embedding.from(vector);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    protected Integer knownDimension() {
        return dimension;
    }
}
