package org.pomelo.api.doc.vo;

import java.time.Instant;

/**
 * 文档切片 VO：用于切片列表展示与编辑。
 */
public record DocChunkVo(
        String id,
        String docId,
        int chunkIndex,
        String content,
        int page,
        int charCount,
        String contentHash,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
