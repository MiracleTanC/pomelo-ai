package org.pomelo.core.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.pomelo.domain.doc.Document;
import org.pomelo.domain.doc.DocMetadata;
import org.pomelo.domain.kb.MetaFieldConfig;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 元数据提取器：按字段配置的提取方式（SYSTEM / DOC_PROPERTY / REGEX / LLM）提取文档元数据。
 * LLM 方式将多个字段合并为一次调用，降低成本。
 */
@Slf4j
@Component
public class MetadataExtractor {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** LLM 提取时截取正文的最大字符数（避免超长 prompt） */
    private static final int MAX_LLM_CONTENT = 3000;

    public MetadataExtractor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 按知识库字段配置提取文档元数据。
     */
    public List<DocMetadata> extract(Document doc, List<MetaFieldConfig> fieldConfigs) {
        List<DocMetadata> results = new ArrayList<>();
        Map<String, String> docProps = null;
        List<MetaFieldConfig> llmFields = new ArrayList<>();

        // 第一轮：处理 SYSTEM / DOC_PROPERTY / REGEX，收集 LLM 字段
        for (MetaFieldConfig fc : fieldConfigs) {
            if (!fc.isEnabled()) continue;
            String value = null;
            try {
                switch (fc.getExtractType()) {
                    case "SYSTEM":
                        value = extractSystem(doc, fc);
                        break;
                    case "DOC_PROPERTY":
                        if (docProps == null) docProps = loadDocProperties(doc);
                        value = docProps.get(fc.getExtractRule());
                        break;
                    case "REGEX":
                        value = extractRegex(doc.getParsedContent(), fc.getExtractRule());
                        break;
                    case "LLM":
                        llmFields.add(fc);
                        continue;
                    default:
                        log.warn("未知的提取方式: {}", fc.getExtractType());
                }
            } catch (Exception e) {
                log.warn("提取字段 {} 失败: {}", fc.getFieldKey(), e.getMessage());
            }
            if (value != null && !value.isBlank()) {
                results.add(buildMetadata(doc, fc, value));
            }
        }

        // 第二轮：LLM 批量提取（合并为一次调用）
        if (!llmFields.isEmpty()) {
            results.addAll(extractByLlm(doc, llmFields));
        }

        return results;
    }

    /**
     * LLM 批量提取：将多个字段合并为一次 prompt 调用，要求返回 JSON。
     */
    private List<DocMetadata> extractByLlm(Document doc, List<MetaFieldConfig> llmFields) {
        List<DocMetadata> results = new ArrayList<>();
        String content = doc.getParsedContent();
        if (content == null || content.isBlank()) {
            return results;
        }

        // 截取正文
        String truncated = content.length() > MAX_LLM_CONTENT
                ? content.substring(0, MAX_LLM_CONTENT) : content;

        // 构建 JSON 字段说明
        StringBuilder fieldDesc = new StringBuilder();
        for (MetaFieldConfig fc : llmFields) {
            fieldDesc.append("- ").append(fc.getFieldKey()).append(": ");
            if (fc.getExtractRule() != null && !fc.getExtractRule().isBlank()) {
                fieldDesc.append(fc.getExtractRule());
            } else {
                fieldDesc.append(fc.getFieldName());
            }
            fieldDesc.append("\n");
        }

        String prompt = """
                请分析以下文档内容，提取元数据并以 JSON 格式返回。只返回 JSON，不要其他内容。
                JSON 字段说明：
                %s
                注意：
                - keywords 和 terms 字段用逗号分隔的字符串
                - metrics 字段用 JSON 数组字符串，每项含 name/value/unit
                - 如果无法提取某字段，返回空字符串
                - summary 限制在 200 字以内

                文档内容：
                %s
                """.formatted(fieldDesc, truncated);

        try {
            String resp = chatModel.chat(prompt).trim();
            // 提取 JSON 部分（LLM 可能在 JSON 前后有多余文本）
            String json = extractJson(resp);
            if (json == null) {
                log.warn("LLM 元数据提取未返回有效 JSON: {}", doc.getName());
                return results;
            }

            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            for (MetaFieldConfig fc : llmFields) {
                Object val = map.get(fc.getFieldKey());
                if (val == null) continue;
                String strVal = val instanceof List
                        ? objectMapper.writeValueAsString(val)
                        : String.valueOf(val).trim();
                if (!strVal.isBlank() && !"null".equals(strVal)) {
                    results.add(buildMetadata(doc, fc, strVal));
                }
            }
            log.info("LLM 元数据提取完成: {} -> {} 字段", doc.getName(), results.size());
        } catch (Exception e) {
            log.warn("LLM 元数据提取失败: {} -> {}", doc.getName(), e.getMessage());
        }
        return results;
    }

    /** 从可能包含其他文本的响应中提取 JSON 对象 */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        // 尝试直接解析
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            int end = trimmed.lastIndexOf('}');
            if (end > 0) return trimmed.substring(0, end + 1);
        }
        // 查找第一个 { 到最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private DocMetadata buildMetadata(Document doc, MetaFieldConfig fc, String value) {
        return DocMetadata.builder()
                .docId(doc.getId())
                .kbId(doc.getKbId())
                .fieldKey(fc.getFieldKey())
                .fieldName(fc.getFieldName())
                .fieldValue(value)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private String extractSystem(Document doc, MetaFieldConfig fc) {
        return switch (fc.getFieldKey()) {
            case "file_name" -> doc.getName();
            case "create_time" -> doc.getCreatedAt() != null
                    ? DATETIME_FMT.format(doc.getCreatedAt()) : null;
            case "vector_status" -> doc.getStatus() != null
                    ? doc.getStatus().getLabel() : null;
            default -> null;
        };
    }

    private Map<String, String> loadDocProperties(Document doc) {
        Map<String, String> props = new HashMap<>();
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) return props;
        Path path = Path.of(doc.getFilePath());
        String name = doc.getName() == null ? "" : doc.getName().toLowerCase();
        try {
            if (name.endsWith(".pdf")) {
                try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
                    PDDocumentInformation info = pdf.getDocumentInformation();
                    putIfNotNull(props, "title", info.getTitle());
                    putIfNotNull(props, "author", info.getAuthor());
                    putIfNotNull(props, "subject", info.getSubject());
                    putIfNotNull(props, "keywords", info.getKeywords());
                    putIfNotNull(props, "creator", info.getCreator());
                    putIfNotNull(props, "producer", info.getProducer());
                    if (info.getCreationDate() != null) {
                        putIfNotNull(props, "creationDate",
                                DATE_FMT.format(Instant.ofEpochMilli(info.getCreationDate().getTimeInMillis())));
                    }
                    if (info.getModificationDate() != null) {
                        putIfNotNull(props, "modDate",
                                DATE_FMT.format(Instant.ofEpochMilli(info.getModificationDate().getTimeInMillis())));
                    }
                }
            } else if (name.endsWith(".docx")) {
                try (FileInputStream fis = new FileInputStream(path.toFile());
                     XWPFDocument word = new XWPFDocument(fis)) {
                    var core = word.getProperties().getCoreProperties();
                    putIfNotNull(props, "title", core.getTitle());
                    putIfNotNull(props, "author", core.getCreator());
                    putIfNotNull(props, "subject", core.getSubject());
                    putIfNotNull(props, "keywords", core.getKeywords());
                    if (core.getCreated() != null) {
                        putIfNotNull(props, "creationDate", DATE_FMT.format(core.getCreated().toInstant()));
                    }
                    if (core.getModified() != null) {
                        putIfNotNull(props, "modDate", DATE_FMT.format(core.getModified().toInstant()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取文档元数据失败: {}", e.getMessage());
        }
        return props;
    }

    private String extractRegex(String content, String regex) {
        if (content == null || content.isBlank() || regex == null || regex.isBlank()) return null;
        try {
            Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0);
            }
        } catch (Exception e) {
            log.warn("正则提取失败: {} -> {}", regex, e.getMessage());
        }
        return null;
    }

    private void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value.trim());
    }
}
