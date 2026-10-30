package org.pomelo.core.chunk;

import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.chunk.TextChunk;
import org.pomelo.domain.parser.ParsedBlock;
import org.pomelo.domain.parser.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按标题层级切片：识别 Markdown 风格的标题（# ~ ######）或中文编号（一、/1./（一）），
 * 按标题分段，超出 chunkSize 时再用通用切分兜底。保留层级上下文前缀。
 */
@Slf4j
@Component
public class HeadingChunker implements ChunkStrategy {

    private static final Pattern HEADING = Pattern.compile(
            "^(#{1,6}\\s.+|第[\\d一二三四五六七八九十百]+[章节条部分]|\\d+\\.\\s|\\([一二三四五六七八九十]+\\)|[一二三四五六七八九十]+、)");

    @Override
    public String name() {
        return "heading";
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument doc, int chunkSize, int overlap) {
        List<TextChunk> result = new ArrayList<>();
        int index = 0;
        String currentHeading = null;
        StringBuilder currentSection = new StringBuilder();

        for (ParsedBlock block : doc.blocks()) {
            String text = block.text();
            boolean isHeading = HEADING.matcher(text).find();

            if (isHeading) {
                // 遇到新标题，先保存上一段
                if (!currentSection.isEmpty()) {
                    String sectionText = currentSection.toString().strip();
                    if (!sectionText.isBlank()) {
                        if (sectionText.length() > chunkSize) {
                            // 超长段落兜底切分
                            result.addAll(splitLong(sectionText, chunkSize, overlap,
                                    block.page(), index));
                            index += countChunks(sectionText, chunkSize, overlap);
                        } else {
                            result.add(TextChunk.of(sectionText, block.page(), index++));
                        }
                    }
                    currentSection.setLength(0);
                }
                currentHeading = text.strip();
                currentSection.append(text).append("\n");
            } else {
                currentSection.append(text).append("\n");
                // 超长时切分
                if (currentSection.length() >= chunkSize) {
                    String sectionText = currentSection.toString().strip();
                    String prefix = currentHeading != null ? currentHeading + "\n" : "";
                    if (sectionText.length() > chunkSize) {
                        result.addAll(splitLong(prefix + sectionText, chunkSize, overlap,
                                block.page(), index));
                        index += countChunks(prefix + sectionText, chunkSize, overlap);
                    } else {
                        result.add(TextChunk.of(prefix + sectionText, block.page(), index++));
                    }
                    currentSection.setLength(0);
                }
            }
        }

        // 收尾
        if (!currentSection.isEmpty()) {
            String sectionText = currentSection.toString().strip();
            if (!sectionText.isBlank()) {
                if (sectionText.length() > chunkSize) {
                    result.addAll(splitLong(sectionText, chunkSize, overlap, 0, index));
                } else {
                    result.add(TextChunk.of(sectionText, 0, index++));
                }
            }
        }

        log.info("标题层级切片完成: {} 切片", result.size());
        return result;
    }

    private List<TextChunk> splitLong(String text, int size, int overlap, int page, int startIndex) {
        List<TextChunk> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size - overlap) {
            int end = Math.min(i + size, text.length());
            String piece = text.substring(i, end).strip();
            if (!piece.isBlank()) {
                result.add(TextChunk.of(piece, page, startIndex + result.size()));
            }
            if (end == text.length()) break;
        }
        return result;
    }

    private int countChunks(String text, int size, int overlap) {
        int count = 0;
        for (int i = 0; i < text.length(); i += size - overlap) {
            count++;
            if (i + size >= text.length()) break;
        }
        return count;
    }
}
