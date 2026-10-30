package org.pomelo.api.doc.vo;

import org.pomelo.common.enums.DocStatus;

import java.time.Instant;
import java.util.List;

/**
 * 文档详情 VO：含原文内容、切片列表、元数据。
 */
public record DocDetailVo(
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
        Instant vectorizedAt,
        String parsedContent,
        List<DocChunkVo> chunks,
        List<DocMetadataVo> metadata
) {
}
