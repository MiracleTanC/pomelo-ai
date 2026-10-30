package org.pomelo.api.chat.vo;

import org.pomelo.domain.rag.Citation;

import java.time.Instant;
import java.util.List;

public record ChatMessageVo(
        String id,
        String sessionId,
        String role,
        String content,
        List<Citation> citations,
        Instant createdAt
) {
}
