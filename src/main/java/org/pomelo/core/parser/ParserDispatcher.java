package org.pomelo.core.parser;
import org.pomelo.domain.parser.ParsedDocument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 解析器选择：MinerU 启用时优先用 MinerU，失败或不可用时回退本地解析器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParserDispatcher {

    private final MineruHttpParser mineruParser;
    private final LocalParser localParser;

    @Value("${pomelo.mineru.enabled}")
    private boolean mineruEnabled;

    /**
     * 解析文件（不传 objectPath，LocalParser 等不需要文件来源信息）。
     */
    public ParsedDocument parse(Path file, String fileName) {
        return parse(file, fileName, null);
    }

    /**
     * 解析文件，传入 OSS 对象路径（MinerU 云端模式用于生成预签名 URL）。
     */
    public ParsedDocument parse(Path file, String fileName, String objectPath) {
        return parse(file, fileName, objectPath, null);
    }

    /**
     * 解析文件，传入 OSS 对象路径和文档 ID。
     *
     * @param objectPath 文件在 OSS 中的路径（MinerU 云端预签名 URL 用）
     * @param docId 文档 ID（MinerU 结果 ZIP 图片上传 OSS 路径隔离用）
     */
    public ParsedDocument parse(Path file, String fileName, String objectPath, String docId) {
        if (mineruEnabled && mineruParser.supports(fileName)) {
            try {
                return mineruParser.parse(file, fileName, objectPath, docId);
            } catch (Exception e) {
                log.warn("MinerU 解析失败，回退本地解析器: {}", e.getMessage());
            }
        }
        try {
            return localParser.parse(file, fileName);
        } catch (Exception e) {
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }
}
