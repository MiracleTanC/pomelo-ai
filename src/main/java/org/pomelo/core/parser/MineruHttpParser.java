package org.pomelo.core.parser;

import org.pomelo.core.parser.mineru.MineruClient;
import org.pomelo.domain.parser.ParsedBlock;
import org.pomelo.domain.parser.ParsedDocument;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MinerU 深度解析器：通过 {@link MineruClient} 调用本地自建或官方云端 MinerU 服务。
 * <p>
 * 支持两种模式（由 pomelo.mineru.mode 配置）：
 * <ul>
 *   <li><b>local</b>：连接自建 MinerU API Server（4.1 Tasks API：JSON manifest + PUT 上传 + ZIP 下载）</li>
 *   <li><b>cloud</b>：连接官方云端（旧版 Extract API：multipart 上传 + 轮询返回 Markdown/ContentList）</li>
 * </ul>
 * 两种模式均：提交任务 → 轮询 → 提取结果 → 分块标注
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MineruHttpParser implements DocumentParser {

    private final MineruClient mineruClient;

    /** 轮询间隔（秒） */
    private static final int POLL_INTERVAL = 3;
    /** 最大轮询次数 */
    private static final int MAX_POLLS = 200;

    private static final Pattern TABLE_PATTERN = Pattern.compile("(?s)(\\|.+\\|\\n)+\\|[=:\\-\\s|]+\\n(\\|.+\\|\\n?)+");
    private static final Pattern FORMULA_PATTERN = Pattern.compile("(\\$\\$.+?\\$\\$|\\$.+?\\$)");

    /**
     * 解析文件。
     *
     * @param file     本地临时文件（已从 OSS 下载）
     * @param fileName 原始文件名
     * @param objectPath 文件在 OSS 中的对象路径（cloud 模式用于生成预签名 URL）
     */
    /** 2 参数版（接口抽象方法实现，objectPath=null 时降级） */
    @Override
    public ParsedDocument parse(Path file, String fileName) throws Exception {
        return parse(file, fileName, null);
    }

    /**
     * 带文件来源信息的解析。
     *
     * @param file     本地临时文件（已从 OSS 下载）
     * @param fileName 原始文件名
     * @param objectPath 文件在 OSS 中的对象路径（cloud 模式用于生成预签名 URL）
     */
    public ParsedDocument parse(Path file, String fileName, String objectPath) throws Exception {
        return parse(file, fileName, objectPath, null);
    }

    /**
     * 带文件来源和文档 ID 的解析。
     *
     * @param file     本地临时文件（已从 OSS 下载）
     * @param fileName 原始文件名
     * @param objectPath 文件在 OSS 中的对象路径（cloud 模式用于生成预签名 URL）
     * @param docId 文档 ID（用于结果 ZIP 图片上传 OSS 的路径隔离）
     */
    @Override
    public ParsedDocument parse(Path file, String fileName, String objectPath, String docId) throws Exception {
        // 1. 提交任务（cloud URL 模式 / cloud 上传模式 / local 模式）
        MineruClient.TaskHandle handle = mineruClient.submitTask(file, fileName, objectPath);

        // 2. 轮询直到完成
        JsonNode result = pollTask(handle, fileName);
        if (result == null) {
            throw new RuntimeException("MinerU[" + mineruClient.getMode() + "] 任务超时: " + fileName);
        }

        // 3. 提取结果
        MineruClient.MineruResult mr = mineruClient.extractResult(result, handle);

        // 失败状态
        if ("failed".equals(mr.state()) || "error".equals(mr.state())) {
            throw new RuntimeException("MinerU[" + mineruClient.getMode() + "] 解析失败: " + mr.errMsg());
        }

        // 优先 Content List（结构化），其次 Markdown（文本），最后下载 ZIP
        if (mr.hasContentList()) {
            return parseContentList(mr.contentList(), fileName);
        }
        if (mr.hasMarkdown()) {
            return splitMarkdownEnhanced(mr.markdown(), fileName);
        }
        if (mr.hasResultUrl()) {
            // 下载 ZIP 提取 full.md，同时上传图片到 OSS
            String markdown = mineruClient.downloadResultMarkdown(mr.resultUrl(), docId);
            return splitMarkdownEnhanced(markdown, fileName);
        }
        throw new RuntimeException("MinerU[" + mineruClient.getMode() + "] 返回为空: " + fileName);
    }

    /**
     * 轮询任务直到完成或超时。
     */
    private JsonNode pollTask(MineruClient.TaskHandle handle, String fileName) throws Exception {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonNode root = mineruClient.queryTask(handle);
            if (root == null) {
                Thread.sleep(POLL_INTERVAL * 1000L);
                continue;
            }

            // 兼容多种状态路径
            JsonNode data = root.path("data");
            String state;

            // 上传模式（batch）：data.extract_result[0].state
            if (handle.isBatch()) {
                JsonNode results = data.path("extract_result");
                if (results.isArray() && !results.isEmpty()) {
                    state = results.get(0).path("state").asText("");
                } else {
                    state = "";
                }
            } else {
                // URL 模式 / local：data.state 或 status
                state = data.path("state").asText(
                        root.path("status").asText(
                                data.path("status").asText("")));
            }

            int progress = data.path("progress").path("completed").asInt(
                    data.path("progress").asInt(0));

            log.debug("MinerU[{}] 轮询[{}/{}]: {} state={} progress={}",
                    mineruClient.getMode(), i + 1, MAX_POLLS, fileName, state, progress);

            // 终态判断
            if ("done".equals(state) || "success".equals(state) || "completed".equals(state)) {
                log.info("MinerU[{}] 任务完成: {} state={}", mineruClient.getMode(), fileName, state);
                return root;
            }
            if ("failed".equals(state) || "error".equals(state)) {
                log.warn("MinerU[{}] 任务失败: {} state={}", mineruClient.getMode(), fileName, state);
                return root; // 返回让 extractResult 处理错误信息
            }
            Thread.sleep(POLL_INTERVAL * 1000L);
        }
        return null;
    }

    /**
     * 从 Content List（结构化）解析：每项有 type（text/table/image/equation）+ text/page。
     */
    private ParsedDocument parseContentList(JsonNode contentList, String fileName) {
        List<ParsedBlock> blocks = new ArrayList<>();
        int currentPage = 0;

        for (JsonNode item : contentList) {
            String type = item.path("type").asText("text");
            String text = item.path("text").asText("");
            int page = item.path("page_idx").asInt(item.path("page").asInt(0)) + 1;
            if (page > 0) currentPage = page;

            if (text.isBlank()) continue;

            String annotatedText = switch (type) {
                case "table" -> "[表格]\n" + text;
                case "image" -> "[图片] " + item.path("img_caption").asText(
                        item.path("text").asText(""));
                case "equation" -> "[公式] " + text;
                default -> text;
            };

            if (!annotatedText.isBlank()) {
                blocks.add(ParsedBlock.of(annotatedText, currentPage));
            }
        }

        log.info("MinerU[{}] Content List 解析: {} -> {} 块",
                mineruClient.getMode(), fileName, blocks.size());
        return ParsedDocument.of(blocks);
    }

    /**
     * 增强版 Markdown 分块：按标题分块，标注表格/公式。
     */
    private ParsedDocument splitMarkdownEnhanced(String markdown, String fileName) {
        List<ParsedBlock> blocks = new ArrayList<>();

        String[] sections = markdown.split("\\n(?=#{1,6}\\s|\\|)", -1);
        StringBuilder current = new StringBuilder();
        int page = 0;

        for (String section : sections) {
            String s = section.strip();
            if (s.isBlank()) continue;

            if (s.startsWith("#")) {
                if (!current.isEmpty()) {
                    blocks.add(annotateBlock(current.toString(), page));
                    current.setLength(0);
                }
                current.append(s).append("\n");
            } else {
                current.append(s).append("\n");
            }

            if (TABLE_PATTERN.matcher(s).find()) {
                current.append("<!-- 表格 -->\n");
            }
        }
        if (!current.isEmpty()) {
            blocks.add(annotateBlock(current.toString(), page));
        }

        log.info("MinerU[{}] Markdown 解析: {} -> {} chars, {} 块",
                mineruClient.getMode(), fileName, markdown.length(), blocks.size());
        return ParsedDocument.of(blocks);
    }

    private ParsedBlock annotateBlock(String text, int page) {
        String annotated = text;
        Matcher tableMatcher = TABLE_PATTERN.matcher(text);
        if (tableMatcher.find()) {
            annotated = "[含表格] " + text;
        }
        Matcher formulaMatcher = FORMULA_PATTERN.matcher(text);
        if (formulaMatcher.find()) {
            annotated = annotated.startsWith("[含表格]") ? annotated : "[含公式] " + text;
        }
        return ParsedBlock.of(annotated, page);
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase();
        return n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".doc")
                || n.endsWith(".ppt") || n.endsWith(".pptx") || n.endsWith(".xls") || n.endsWith(".xlsx");
    }
}
