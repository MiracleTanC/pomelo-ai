package org.pomelo.domain.doc;

import lombok.*;

import java.time.Instant;

/**
 * 文档切片：每个切片持久化存储，支持切片预览/编辑/增量向量化。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocChunk {

    private String id;

    private String docId;

    private String kbId;

    private int chunkIndex;

    /** 切片内容 */
    private String content;

    private int page;

    private int charCount;

    /** 内容哈希，用于增量向量化检测变更 */
    private String contentHash;

    /** 切片向量化状态 */
    @Builder.Default
    private ChunkStatus status = ChunkStatus.PENDING;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    public enum ChunkStatus {
        /** 待向量化 */
        PENDING,
        /** 已向量化 */
        VECTORIZED,
        /** 向量化失败 */
        FAILED,
        /** 内容已编辑，需重新向量化 */
        EDITED,
        /** 过期（重新切片后旧切片标记） */
        STALE
    }
}
