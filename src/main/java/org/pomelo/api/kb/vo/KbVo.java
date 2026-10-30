package org.pomelo.api.kb.vo;

import java.time.Instant;

public record KbVo(
        String id,
        String name,
        String description,
        String collectionName,
        int docCount,
        int chunkCount,
        int chunkSize,
        int chunkOverlap,
        int topK,
        int rerankTopK,
        double similarityThreshold,
        boolean autoVectorize,
        String chunkStrategy,
        Instant createdAt,
        Instant updatedAt
) {
}
