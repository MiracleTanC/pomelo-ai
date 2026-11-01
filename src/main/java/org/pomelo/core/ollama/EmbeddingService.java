package org.pomelo.core.ollama;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.chunk.TextChunk;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 嵌入服务：封装 langchain4j EmbeddingModel，按批向量化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * 批量向量化文本块，返回与输入同序的向量列表。
     */
    public List<float[]> embedBatch(List<TextChunk> chunks) {
        log.info(">>> [EMBED] 开始批量向量化: {} 个文本块, model={}", chunks.size(),
                embeddingModel.getClass().getSimpleName());
        long t0 = System.currentTimeMillis();

        List<TextSegment> segments = chunks.stream()
                .map(c -> TextSegment.from(c.text()))
                .toList();

        List<float[]> vectors = embeddingModel.embedAll(segments).content().stream()
                .map(Embedding::vector)
                .toList();

        long elapsed = System.currentTimeMillis() - t0;
        log.info("<<< [EMBED] 批量向量化完成: {} 个向量, 维度={}, 耗时 {}ms (avg {}ms/条)",
                vectors.size(),
                vectors.isEmpty() ? 0 : vectors.get(0).length,
                elapsed,
                vectors.isEmpty() ? 0 : elapsed / vectors.size());
        return vectors;
    }

    /**
     * 单条文本向量化（用于查询）。
     */
    public float[] embedQuery(String query) {
        log.info(">>> [EMBED] 查询向量化: query=\"{}\" ({}字符)",
                query.length() > 50 ? query.substring(0, 50) + "..." : query, query.length());
        long t0 = System.currentTimeMillis();

        float[] vec = embeddingModel.embed(query).content().vector();

        log.info("<<< [EMBED] 查询向量化完成: 维度={}, 耗时 {}ms", vec.length, System.currentTimeMillis() - t0);
        return vec;
    }
}
