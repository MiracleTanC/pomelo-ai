package org.pomelo.core.parser;
import org.pomelo.domain.parser.ParsedDocument;
import org.pomelo.domain.parser.ParsedBlock;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地轻量解析器：MinerU 不可用时的兜底。
 * - PDF：Apache PDFBox，按页提取文本
 * - Word(.docx)：Apache POI，按段落提取
 * - TXT/MD：直接读取
 */
@Slf4j
@Component
public class LocalParser implements DocumentParser {

    @Override
    public ParsedDocument parse(Path file, String fileName) throws Exception {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (n.endsWith(".pdf")) {
            return parsePdf(file);
        }
        if (n.endsWith(".docx")) {
            return parseDocx(file);
        }
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".markdown")) {
            return parseText(file);
        }
        throw new IllegalArgumentException("本地解析器不支持该文件类型: " + fileName);
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String n = fileName.toLowerCase();
        return n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".txt")
                || n.endsWith(".md") || n.endsWith(".markdown");
    }

    private ParsedDocument parsePdf(Path file) throws Exception {
        List<ParsedBlock> blocks = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);
                if (text != null && !text.isBlank()) {
                    // 按空行切段
                    for (String para : text.split("\\n\\s*\\n")) {
                        String t = para.strip();
                        if (!t.isBlank()) {
                            blocks.add(ParsedBlock.of(t, i));
                        }
                    }
                }
            }
        }
        log.info("PDFBox 解析完成: {}, 共 {} 段", fileName(file), blocks.size());
        return ParsedDocument.of(blocks);
    }

    private ParsedDocument parseDocx(Path file) throws Exception {
        List<ParsedBlock> blocks = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    blocks.add(ParsedBlock.of(t.strip(), 0));
                }
            }
        }
        log.info("POI 解析 docx 完成: {}, 共 {} 段", fileName(file), blocks.size());
        return ParsedDocument.of(blocks);
    }

    private ParsedDocument parseText(Path file) throws Exception {
        String content = java.nio.file.Files.readString(file);
        List<ParsedBlock> blocks = new ArrayList<>();
        for (String para : content.split("\\n\\s*\\n")) {
            String t = para.strip();
            if (!t.isBlank()) {
                blocks.add(ParsedBlock.of(t, 0));
            }
        }
        return ParsedDocument.of(blocks);
    }

    private String fileName(Path file) {
        return file != null ? file.getFileName().toString() : "?";
    }
}
