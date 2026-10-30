package org.pomelo.core.chunk;

import org.pomelo.domain.chunk.TextChunk;
import org.pomelo.domain.parser.ParsedDocument;

import java.util.List;

/**
 * 切片策略接口：不同策略实现不同的切分方式。
 */
public interface ChunkStrategy {

    /** 策略名称 */
    String name();

    /**
     * 切分文档。
     *
     * @param doc       已解析的文档
     * @param chunkSize 最大切片字符数
     * @param overlap   重叠字符数
     * @return 切片列表
     */
    List<TextChunk> chunk(ParsedDocument doc, int chunkSize, int overlap);
}
