package org.pomelo.domain.rag;

/**
 * 检索命中结果：切片内容 + 元数据 + 相似度分数。
 */
public record RetrievedChunk(
        String chunkId,
        String docId,
        String docName,
        String kbId,
        int page,
        int chunkIndex,
        String text,
        float score
) {
    public static RetrievedChunk of(String chunkId, String docId, String docName,
                                    String kbId, int page, int chunkIndex,
                                    String text, float score) {
        return new RetrievedChunk(chunkId, docId, docName, kbId, page, chunkIndex, text, score);
    }
}
