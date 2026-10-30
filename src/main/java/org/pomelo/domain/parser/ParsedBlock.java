package org.pomelo.domain.parser;

/**
 * 文档解析后的最小单元：一段文本 + 其所在页码（1-based，0 表示无分页信息）。
 */
public record ParsedBlock(
        String text,
        int page
) {
    public static ParsedBlock of(String text, int page) {
        return new ParsedBlock(text, page);
    }
}
