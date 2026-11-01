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
     * 带文件来源信息的解析（用于 MinerU 云端需要生成预签名 URL 的场景）。
     * 默认实现忽略 objectPath，直接调用无参版本。
     *
     * @param file 文件路径（已下载到本地临时文件）
     * @param fileName 原始文件名
     * @param objectPath 文件在 OSS 中的对象路径（可为 null）
     */
    default ParsedDocument parse(Path file, String fileName, String objectPath) throws Exception {
        return parse(file, fileName);
    }

    /**
     * 带文档 ID 的解析（用于 MinerU 结果 ZIP 中图片上传 OSS 的路径隔离）。
     * 默认实现忽略 docId，直接调用三参数版本。
     *
     * @param file 文件路径
     * @param fileName 原始文件名
     * @param objectPath 文件在 OSS 中的对象路径（可为 null）
     * @param docId 文档 ID（用于图片 OSS 路径隔离，可为 null）
     */
    default ParsedDocument parse(Path file, String fileName, String objectPath, String docId) throws Exception {
        return parse(file, fileName, objectPath);
    }

    /**
     * 当前实现是否支持该文件类型。
     */
    boolean supports(String fileName);
}
