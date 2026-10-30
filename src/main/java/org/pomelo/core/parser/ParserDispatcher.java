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

    public ParsedDocument parse(Path file, String fileName) {
        if (mineruEnabled && mineruParser.supports(fileName)) {
            try {
                return mineruParser.parse(file, fileName);
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
