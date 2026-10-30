package org.pomelo.core.parser;
import org.pomelo.domain.parser.ParsedDocument;

import java.nio.file.Path;

/**
 * 文档解析 SPI。
 * <p>
 * 实现可选择调用外部 MinerU HTTP 服务（富文本/表格/公式），或使用本地轻量解析器兜底。
 */
public interface DocumentParser {

    /**
     * @param file 文件路径
     * @param fileName 原始文件名（用于判断扩展名）
     */
    ParsedDocument parse(Path file, String fileName) throws Exception;

    /**
     * 当前实现是否支持该文件类型。
     */
    boolean supports(String fileName);
}
