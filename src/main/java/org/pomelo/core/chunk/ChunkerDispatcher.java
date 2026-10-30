package org.pomelo.core.chunk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.chunk.TextChunk;
import org.pomelo.domain.parser.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切片分发器：按知识库的 chunkStrategy 选择对应切分器。
 * general  → RecursiveCharChunker
 * heading  → HeadingChunker
 * qa       → QaChunker
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkerDispatcher {

    private final RecursiveCharChunker generalChunker;
    private final HeadingChunker headingChunker;
    private final QaChunker qaChunker;

    public List<TextChunk> chunk(ParsedDocument doc, String strategy, int chunkSize, int overlap) {
        ChunkStrategy chunker = switch (strategy == null ? "general" : strategy) {
            case "heading" -> headingChunker;
            case "qa" -> qaChunker;
            default -> generalChunker;
        };
        log.info("使用切片策略: {} ({})", chunker.name(), strategy);
        return chunker.chunk(doc, chunkSize, overlap);
    }

    /** 默认用 general 策略 + 配置的 chunkSize/overlap */
    public List<TextChunk> chunk(ParsedDocument doc, int chunkSize, int overlap) {
        return chunk(doc, "general", chunkSize, overlap);
    }
}
