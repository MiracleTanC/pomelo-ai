package org.pomelo.api.kb.req;

import jakarta.validation.constraints.NotBlank;

public record KbCreateRequest(
        @NotBlank String name,
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
