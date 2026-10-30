package org.pomelo.domain.chunk;

/**
 * 切块结果：一段文本 + 元数据（来源页码、切片序号）。
 */
public record TextChunk(
        String text,
        int page,
        int index
) {
    public static TextChunk of(String text, int page, int index) {
        return new TextChunk(text, page, index);
    }
}
