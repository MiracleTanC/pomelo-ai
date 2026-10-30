package org.pomelo.api.chat.vo;

import org.pomelo.domain.rag.Citation;

import java.util.List;

public record ChatAnswer(
        String sessionId,
        String answer,
        String effectiveQuery,
        List<Citation> citations
) {
}
