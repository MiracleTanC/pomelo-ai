package org.pomelo.domain.rag;

/**
 * 引用来源：回答后展示给用户。
 */
public record Citation(
        String docId,
        String docName,
        int page,
        int chunkIndex,
        String snippet
) {
    public static Citation from(RetrievedChunk c, int snippetLen) {
        String snippet = c.text();
        if (snippet != null && snippet.length() > snippetLen) {
            snippet = snippet.substring(0, snippetLen) + "…";
        }
        return new Citation(c.docId(), c.docName(), c.page(), c.chunkIndex(), snippet);
    }
}
