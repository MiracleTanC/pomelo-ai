package org.pomelo.domain.parser;

import java.util.List;

/**
 * 文档解析结果：原始文本被切分为按页/块的结构化片段，便于后续切块与引用定位。
 */
public record ParsedDocument(
        int totalPages,
        List<ParsedBlock> blocks
) {
    public static ParsedDocument of(List<ParsedBlock> blocks) {
        int pages = blocks.stream().mapToInt(ParsedBlock::page).max().orElse(0);
        return new ParsedDocument(Math.max(pages, 0), blocks);
    }
}
