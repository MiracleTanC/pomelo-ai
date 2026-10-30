package org.pomelo.api.chat.req;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        String sessionId,
        @NotBlank String message,
        String kbId
) {
}
