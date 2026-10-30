package org.pomelo.api.kb.req;

public record KbUpdateRequest(
        String name,
        String description,
        Integer chunkSize,
        Integer chunkOverlap,
        Integer topK,
        Integer rerankTopK,
        Double similarityThreshold,
        Boolean autoVectorize,
        String chunkStrategy
) {
}
