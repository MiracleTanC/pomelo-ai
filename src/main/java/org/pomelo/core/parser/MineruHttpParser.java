package org.pomelo.core.parser;

import org.pomelo.domain.parser.ParsedBlock;
import org.pomelo.domain.parser.ParsedDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MinerU 4.0 深度集成解析器：
 * <p>
 * - 调用 MinerU V1 API 提取 Markdown + Content List
 * - 识别表格（table markdown）、公式（latex）、图片（image 路径）
 * - 按标题层级分块，保留表格/公式结构化信息
 * - 支持异步轮询模式（MinerU 任务可能需要处理时间）
 */
@Slf4j
@Component
public class MineruHttpParser implements DocumentParser {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${pomelo.mineru.base-url}")
    private String baseUrl;

    @Value("${pomelo.mineru.tier}")
    private String tier;

    @Value("${pomelo.mineru.timeout-seconds}")
    private long timeoutSeconds;

    /** 轮询间隔（秒） */
    private static final int POLL_INTERVAL = 3;
    /** 最大轮询次数 */
    private static final int MAX_POLLS = 60;

    /** 识别 Markdown 中的表格和公式 */
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?s)(\\|.+\\|\\n)+\\|[=:\\-\\s|]+\\n(\\|.+\\|\\n?)+");
    private static final Pattern FORMULA_PATTERN = Pattern.compile("(\\$\\$.+?\\$\\$|\\$.+?\\$)");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    @Override
    public ParsedDocument parse(Path file, String fileName) throws Exception {
        // 1. 上传文件创建提取任务
        String taskId = submitTask(file, fileName);
        log.info("MinerU 任务已提交: {} -> taskId={}", fileName, taskId);

        // 2. 轮询任务状态
        JsonNode result = pollTask(taskId, fileName);
        if (result == null) {
            throw new RuntimeException("MinerU 任务超时或失败: " + fileName);
        }

        // 3. 解析结果：优先 content_list（结构化），否则用 markdown
        JsonNode contentList = result.path("data").path("content_list");
        String markdown = result.path("data").path("markdown").asText(
                result.path("markdown").asText(""));

        if (contentList.isArray() && !contentList.isEmpty()) {
            return parseContentList(contentList, fileName);
        }
        if (!markdown.isBlank()) {
            return splitMarkdownEnhanced(markdown);
        }
        throw new RuntimeException("MinerU 返回为空: " + fileName);
    }

    /**
     * 提交提取任务。
     */
    private String submitTask(Path file, String fileName) throws Exception {
        String boundary = "----pomelo" + System.currentTimeMillis();
        byte[] body = buildMultipartBody(file, fileName, boundary);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/extract/task"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("MinerU 提交失败 HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        String taskId = root.path("data").path("task_id").asText(root.path("task_id").asText(""));
        if (taskId.isBlank()) {
            // 某些版本同步返回完整结果
            taskId = root.path("data").path("id").asText(root.path("id").asText(""));
        }
        if (taskId.isBlank()) {
            throw new RuntimeException("MinerU 未返回 taskId: " + resp.body());
        }
        return taskId;
    }

    /**
     * 轮询任务直到完成或超时。
     */
    private JsonNode pollTask(String taskId, String fileName) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        for (int i = 0; i < MAX_POLLS; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/extract/task/" + taskId))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("MinerU 轮询失败 HTTP {}: {}", resp.statusCode(), resp.body());
                Thread.sleep(POLL_INTERVAL * 1000L);
                continue;
            }

            JsonNode root = mapper.readTree(resp.body());
            String state = root.path("data").path("state").asText(
                    root.path("state").asText(""));
            int progress = root.path("data").path("progress").asInt(
                    root.path("progress").asInt(0));

            log.debug("MinerU 轮询: {} state={} progress={}%", fileName, state, progress);

            if ("done".equals(state) || "success".equals(state) || "completed".equals(state)) {
                return root;
            }
            if ("failed".equals(state) || "error".equals(state)) {
                throw new RuntimeException("MinerU 任务失败: " + root);
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

            // 根据类型标注块类型
            String annotatedText = switch (type) {
                case "table" -> "[表格]\n" + text;
                case "image" -> "[图片] " + (item.path("img_caption").asText(
                        item.path("text").asText("")));
                case "equation" -> "[公式] " + text;
                default -> text;
            };

            if (!annotatedText.isBlank()) {
                blocks.add(ParsedBlock.of(annotatedText, currentPage));
            }
        }

        log.info("MinerU Content List 解析: {} -> {} 块", fileName, blocks.size());
        return ParsedDocument.of(blocks);
    }

    /**
     * 增强版 Markdown 分块：按标题分块，标注表格/公式。
     */
    private ParsedDocument splitMarkdownEnhanced(String markdown) {
        List<ParsedBlock> blocks = new ArrayList<>();

        // 标记表格位置
        String[] sections = markdown.split("\\n(?=#{1,6}\\s|\\|)", -1);
        StringBuilder current = new StringBuilder();
        int page = 0;

        for (String section : sections) {
            String s = section.strip();
            if (s.isBlank()) continue;

            // 检查是否为新标题
            if (s.startsWith("#")) {
                if (!current.isEmpty()) {
                    blocks.add(annotateBlock(current.toString(), page));
                    current.setLength(0);
                }
                current.append(s).append("\n");
            } else {
                current.append(s).append("\n");
            }

            // 表格/公式标注
            if (TABLE_PATTERN.matcher(s).find()) {
                current.append("<!-- 表格 -->\n");
            }
        }
        if (!current.isEmpty()) {
            blocks.add(annotateBlock(current.toString(), page));
        }

        log.info("MinerU Markdown 解析: {} -> {} 块", markdown.length(), blocks.size());
        return ParsedDocument.of(blocks);
    }

    /**
     * 标注块中的表格和公式。
     */
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

    private byte[] buildMultipartBody(Path file, String fileName, String boundary) throws Exception {
        byte[] fileBytes = Files.readAllBytes(file);
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(fileName).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"backend\"\r\n\r\n"
                + "minercpu\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"tier\"\r\n\r\n"
                + tier + "\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(head);
        bos.write(fileBytes);
        bos.write(tail);
        return bos.toByteArray();
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase();
        return n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".doc")
                || n.endsWith(".ppt") || n.endsWith(".pptx") || n.endsWith(".xls") || n.endsWith(".xlsx");
    }
}
