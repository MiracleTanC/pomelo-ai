package org.pomelo.api.doc.vo;

import org.pomelo.common.enums.DocStatus;

import java.time.Instant;

public record DocVo(
        String id,
        String kbId,
        String name,
        long fileSize,
        String mimeType,
        String sha256,
        DocStatus status,
        int chunkCount,
        int vectorizedChunks,
        String errorMsg,
        boolean enabled,
        String folderId,
        Instant createdAt,
        Instant updatedAt,
        Instant parsedAt,
        Instant chunkedAt,
        Instant vectorizedAt
) {
}
