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
        List<TextSegment> segments = chunks.stream()
                .map(c -> TextSegment.from(c.text()))
                .toList();
        return embeddingModel.embedAll(segments).content().stream()
                .map(Embedding::vector)
                .toList();
    }

    /**
     * 单条文本向量化（用于查询）。
     */
    public float[] embedQuery(String query) {
        return embeddingModel.embed(query).content().vector();
    }
}
