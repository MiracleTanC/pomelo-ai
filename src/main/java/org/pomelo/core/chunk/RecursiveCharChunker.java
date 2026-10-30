package org.pomelo.core.chunk;
import org.pomelo.domain.chunk.TextChunk;

import org.pomelo.domain.parser.ParsedBlock;
import org.pomelo.domain.parser.ParsedDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分器：按分隔符层次递归切分，超出 chunkSize 时再按字符切，保留 overlap。
 * 在已解析的块基础上二次切分，保留页码元数据用于引用定位。
 */
@Component
public class RecursiveCharChunker implements ChunkStrategy {

    @Value("${pomelo.chunk.size}")
    private int chunkSize;

    @Value("${pomelo.chunk.overlap}")
    private int overlap;

    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", " ", ""};

    @Override
    public String name() {
        return "general";
    }

    /** 原有 chunk 方法，兼容旧调用 */
    public List<TextChunk> chunk(ParsedDocument doc) {
        return chunk(doc, chunkSize, overlap);
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument doc, int chunkSize, int overlap) {
        List<TextChunk> result = new ArrayList<>();
        int index = 0;
        for (ParsedBlock block : doc.blocks()) {
            List<String> pieces = splitRecursive(block.text(), chunkSize, overlap, 0);
            for (String piece : pieces) {
                String t = piece.strip();
                if (!t.isBlank()) {
                    result.add(TextChunk.of(t, block.page(), index++));
                }
            }
        }
        return result;
    }

    private List<String> splitRecursive(String text, int size, int overlap, int sepIdx) {
        List<String> result = new ArrayList<>();
        if (text.length() <= size) {
            result.add(text);
            return result;
        }
        if (sepIdx >= SEPARATORS.length) {
            // 按字符硬切
            for (int i = 0; i < text.length(); i += size - overlap) {
                int end = Math.min(i + size, text.length());
                result.add(text.substring(i, end));
                if (end == text.length()) {
                    break;
                }
            }
            return result;
        }
        String sep = SEPARATORS[sepIdx];
        String[] parts = sep.isEmpty() ? new String[]{text} : text.split(java.util.regex.Pattern.quote(sep));
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.isEmpty() ? part : current + sep + part;
            if (candidate.length() > size && !current.isEmpty()) {
                result.add(current.toString());
                // 保留 overlap：把上一个块尾部带入下一个块开头
                String tail = current.substring(Math.max(0, current.length() - overlap));
                current = new StringBuilder(tail).append(sep).append(part);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            // 当前块若仍超长，用更细的分隔符继续切
            if (current.length() > size) {
                result.addAll(splitRecursive(current.toString(), size, overlap, sepIdx + 1));
            } else {
                result.add(current.toString());
            }
        }
        return result;
    }
}
