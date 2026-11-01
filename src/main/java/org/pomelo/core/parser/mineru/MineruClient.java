package org.pomelo.core.parser.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.storage.OssStorageService;
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
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;

/**
 * MinerU HTTP 客户端：封装本地自建服务和官方云端 API 的调用差异。
 * <p>
 * <b>cloud 模式</b>（官方云端 https://mineru.net/api/v4）有两种提交方式：
 * <ol>
 *   <li><b>URL 模式</b>：文件已有公网可访问 URL（如 OSS 预签名 URL）
 *     <ul>
 *       <li>POST /extract/task {url, model_version} → 返回 task_id</li>
 *       <li>GET /extract/task/{task_id} → data.state, data.full_zip_url</li>
 *     </ul>
 *   </li>
 *   <li><b>上传模式</b>：文件无公网 URL，上传到 MinerU 的 OSS
 *     <ul>
 *       <li>POST /file-urls/batch {files:[{name}], model_version} → 返回 batch_id + file_urls[]</li>
 *       <li>PUT {file_urls[0]} 上传文件字节（系统自动提交解析任务）</li>
 *       <li>GET /extract-results/batch/{batch_id} → data.extract_result[0].state, .full_zip_url</li>
 *     </ul>
 *   </li>
 * </ol>
 * 
 * <b>local 模式</b>（自建 4.1 API Server）：
 * <ul>
 *   <li>POST /v1/tasks {files:[{file_name,bytes}], tier} → task_id + upload URL</li>
 *   <li>PUT {upload.url} 上传文件</li>
 *   <li>GET /v1/tasks/{task_id} → files[0].result_url</li>
 * </ul>
 * 
 * <b>结果 ZIP 结构</b>（云端和本地一致）：full.md 为 Markdown 解析结果，layout.json 为中间结果，
 * *_content_list.json 为内容列表，*_model.json 为模型推理结果。
 */
@Slf4j
@Component
public class MineruClient {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${pomelo.mineru.mode}")
    private String mode;

    @Value("${pomelo.mineru.local.base-url}")
    private String localBaseUrl;

    @Value("${pomelo.mineru.cloud.base-url}")
    private String cloudBaseUrl;

    @Value("${pomelo.mineru.cloud.api-key}")
    private String cloudApiKey;

    @Value("${pomelo.mineru.cloud.model-version}")
    private String cloudModelVersion;

    @Value("${pomelo.mineru.tier}")
    private String tier;

    @Value("${pomelo.mineru.timeout-seconds}")
    private long timeoutSeconds;

    private final OssStorageService ossStorage;

    private HttpClient httpClient;

    public MineruClient(OssStorageService ossStorage) {
        this.ossStorage = ossStorage;
    }

    private HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        }
        return httpClient;
    }

    public String getMode() {
        return mode;
    }

    public boolean isCloud() {
        return "cloud".equalsIgnoreCase(mode);
    }

    private String baseUrl() {
        return isCloud() ? cloudBaseUrl : localBaseUrl;
    }

    private HttpRequest.Builder requestBuilder(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url));
        if (isCloud() && cloudApiKey != null && !cloudApiKey.isBlank()) {
            b.header("Authorization", "Bearer " + cloudApiKey);
        }
        return b;
    }

    // ==================== 任务提交 ====================

    /**
     * 提交解析任务。返回任务句柄（task_id 或 batch_id + 类型）。
     */
    public TaskHandle submitTask(Path file, String fileName, String objectPath) throws Exception {
        if (isCloud()) {
            return submitTaskCloud(file, fileName, objectPath);
        } else {
            return submitTaskLocal(file, fileName);
        }
    }

    // ==================== cloud 模式 ====================

    private TaskHandle submitTaskCloud(Path file, String fileName, String objectPath) throws Exception {
        // 尝试 URL 模式：用 OSS 预签名 URL
        String fileUrl = null;
        if (objectPath != null && !objectPath.isBlank()) {
            try {
                fileUrl = ossStorage.getPresignedUrl(objectPath, 7 * 24 * 3600);
            } catch (Exception e) {
                log.warn("生成预签名 URL 失败: {}", e.getMessage());
            }
        }

        if (fileUrl != null && !fileUrl.contains("localhost") && !fileUrl.contains("127.0.0.1")) {
            // URL 模式：OSS 公网可访问
            return submitTaskCloudByUrl(fileName, fileUrl);
        } else {
            // 上传模式：上传到 MinerU 的 OSS
            log.info("MinerU[cloud] 使用文件上传模式（OSS 预签名 URL 不可用）");
            return submitTaskCloudByUpload(file, fileName);
        }
    }

    /**
     * URL 模式：POST /extract/task {url, model_version}
     */
    private TaskHandle submitTaskCloudByUrl(String fileName, String fileUrl) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("url", fileUrl);
        body.put("model_version", cloudModelVersion);

        String url = baseUrl() + "/extract/task";
        HttpRequest request = requestBuilder(url)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("MinerU[cloud] URL 模式提交失败 HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }

        JsonNode root = mapper.readTree(resp.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new RuntimeException("MinerU[cloud] URL 模式提交失败 code=" + code + " msg=" + root.path("msg").asText(""));
        }

        String taskId = root.path("data").path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new RuntimeException("MinerU[cloud] 未返回 task_id: " + truncate(resp.body(), 500));
        }

        log.info("MinerU[cloud] 任务已提交（URL 模式）: {} -> taskId={}", fileName, taskId);
        return new TaskHandle(taskId, false);
    }

    /**
     * 上传模式：POST /file-urls/batch → PUT 文件
     * <p>
     * 1. 申请上传链接：POST /file-urls/batch {files:[{name}], model_version}
     *    → 返回 {batch_id, file_urls:[]}
     * 2. PUT 文件字节到 file_urls[0]（系统自动提交解析任务）
     */
    private TaskHandle submitTaskCloudByUpload(Path file, String fileName) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode filesArr = body.putArray("files");
        ObjectNode fileObj = filesArr.addObject();
        fileObj.put("name", fileName);
        body.put("model_version", cloudModelVersion);

        String url = baseUrl() + "/file-urls/batch";
        HttpRequest request = requestBuilder(url)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("MinerU[cloud] 申请上传链接失败 HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }

        JsonNode root = mapper.readTree(resp.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new RuntimeException("MinerU[cloud] 申请上传链接失败 code=" + code + " msg=" + root.path("msg").asText(""));
        }

        String batchId = root.path("data").path("batch_id").asText("");
        JsonNode fileUrls = root.path("data").path("file_urls");
        if (batchId.isBlank() || !fileUrls.isArray() || fileUrls.isEmpty()) {
            throw new RuntimeException("MinerU[cloud] 未返回 batch_id 或 file_urls: " + truncate(resp.body(), 500));
        }

        String uploadUrl = fileUrls.get(0).asText("");

        // PUT 上传文件（MinerU OSS 预签名 URL，不需要 Auth 头）
        HttpRequest uploadReq = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();

        HttpResponse<String> uploadResp = client().send(uploadReq, HttpResponse.BodyHandlers.ofString());
        if (uploadResp.statusCode() < 200 || uploadResp.statusCode() >= 300) {
            throw new RuntimeException("MinerU[cloud] 文件上传失败 HTTP " + uploadResp.statusCode() + ": " + truncate(uploadResp.body(), 500));
        }

        log.info("MinerU[cloud] 文件已上传，任务自动提交: {} -> batchId={}", fileName, batchId);
        return new TaskHandle(batchId, true);
    }

    // ==================== local 模式：4.1 Tasks API ====================

    private TaskHandle submitTaskLocal(Path file, String fileName) throws Exception {
        long fileSize = Files.size(file);
        ObjectNode bodyObj = mapper.createObjectNode();
        bodyObj.put("tier", tier);
        ArrayNode filesArr = bodyObj.putArray("files");
        ObjectNode fileObj = filesArr.addObject();
        fileObj.put("file_name", fileName);
        fileObj.put("bytes", fileSize);
        bodyObj.set("output_formats", mapper.createArrayNode().add("default"));
        String jsonBody = mapper.writeValueAsString(bodyObj);

        String url = baseUrl() + "/v1/tasks";
        HttpRequest request = requestBuilder(url)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 202 && resp.statusCode() != 200) {
            throw new RuntimeException("MinerU[local] 创建任务失败 HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }

        JsonNode root = mapper.readTree(resp.body());
        String taskId = root.path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new RuntimeException("MinerU[local] 未返回 task_id: " + truncate(resp.body(), 500));
        }

        // 上传文件
        JsonNode firstFile = root.path("files").path(0);
        String uploadUrl = firstFile.path("upload").path("url").asText("");
        if (!uploadUrl.isBlank()) {
            uploadFileLocal(uploadUrl, file);
        }

        log.info("MinerU[local] 任务已提交: {} -> taskId={}", fileName, taskId);
        return new TaskHandle(taskId, false);
    }

    private void uploadFileLocal(String uploadUrl, Path file) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Content-Type", "application/octet-stream")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();

        HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("MinerU[local] 上传文件失败 HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }
        log.info("MinerU[local] 文件上传完成");
    }

    // ==================== 任务查询 ====================

    /**
     * 查询任务状态。
     *
     * @param handle 任务句柄（区分 task_id 和 batch_id）
     * @return 任务状态 JSON，或 null
     */
    public JsonNode queryTask(TaskHandle handle) throws Exception {
        String url;
        if (isCloud()) {
            if (handle.isBatch()) {
                // 上传模式：GET /extract-results/batch/{batch_id}
                url = baseUrl() + "/extract-results/batch/" + handle.id();
            } else {
                // URL 模式：GET /extract/task/{task_id}
                url = baseUrl() + "/extract/task/" + handle.id();
            }
        } else {
            url = baseUrl() + "/v1/tasks/" + handle.id();
        }

        HttpRequest request = requestBuilder(url)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("MinerU[{}] 查询失败 HTTP {}: {}", mode, resp.statusCode(), truncate(resp.body(), 200));
            return null;
        }
        return mapper.readTree(resp.body());
    }

    // ==================== 结果提取 ====================

    /**
     * 从查询结果中提取状态和结果 URL。
     */
    public MineruResult extractResult(JsonNode taskResult, TaskHandle handle) {
        JsonNode data = taskResult.path("data");

        // 上传模式：data.extract_result[0]
        if (handle.isBatch()) {
            JsonNode extractResults = data.path("extract_result");
            if (extractResults.isArray() && !extractResults.isEmpty()) {
                JsonNode first = extractResults.get(0);
                String state = first.path("state").asText("");
                String fullZipUrl = first.path("full_zip_url").asText("");
                String errMsg = first.path("err_msg").asText("");
                log.info("MinerU[cloud] batch 结果: state={}, zipUrl={}, err={}",
                        state, fullZipUrl.isEmpty() ? "(无)" : truncate(fullZipUrl, 100), errMsg);
                return new MineruResult(null, "", fullZipUrl, state, errMsg);
            }
        }

        // URL 模式 / local 模式
        String state = data.path("state").asText(
                taskResult.path("status").asText(
                        data.path("status").asText("")));

        String markdown = data.path("markdown").asText(
                taskResult.path("markdown").asText(""));

        // 云端 URL 模式：data.full_zip_url
        String fullZipUrl = data.path("full_zip_url").asText("");

        // local 模式：files[0].result_url
        String resultUrl = "";
        JsonNode files = data.path("files");
        if (files.isMissingNode() || files.isNull()) {
            files = taskResult.path("files");
        }
        if (files.isArray() && !files.isEmpty()) {
            resultUrl = files.get(0).path("result_url").asText("");
        }
        if (!fullZipUrl.isBlank()) {
            resultUrl = fullZipUrl;
        }

        String errMsg = data.path("err_msg").asText("");

        JsonNode contentList = data.path("content_list");
        if (contentList.isMissingNode() || contentList.isNull()) {
            contentList = taskResult.path("content_list");
        }

        log.info("MinerU[{}] 结果: state={}, markdown={}bytes, contentList={}items, zipUrl={}",
                mode, state,
                markdown != null ? markdown.length() : 0,
                (contentList.isArray() ? contentList.size() : 0),
                resultUrl.isEmpty() ? "(无)" : truncate(resultUrl, 100));

        return new MineruResult(contentList, markdown, resultUrl, state, errMsg);
    }

    /**
     * 下载结果 ZIP 并提取 Markdown 内容（不处理图片）。
     * <p>
     * MinerU 结果 ZIP 中 Markdown 文件名为 full.md（非 markdown.md）。
     */
    public String downloadResultMarkdown(String resultUrl) throws Exception {
        return downloadResultMarkdown(resultUrl, null);
    }

    /**
     * 下载结果 ZIP，提取 Markdown，并将 ZIP 中的图片上传到 OSS。
     * <p>
     * MinerU 结果 ZIP 中的 Markdown 用相对路径引用图片（如 ![](images/xxx.jpg)），
     * 前端无法直接访问。本方法将图片上传到 OSS（images/{docId}/{filename}），
     * 并将 Markdown 中的相对路径替换为代理 URL（/api/doc/image/{docId}/{filename}）。
     *
     * @param resultUrl MinerU 结果 ZIP 下载 URL
     * @param docId     文档 ID（用于 OSS 路径隔离），为 null 时不处理图片
     */
    public String downloadResultMarkdown(String resultUrl, String docId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resultUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();

        // 同源请求添加 Auth
        if (resultUrl.startsWith(baseUrl()) && isCloud() && cloudApiKey != null && !cloudApiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + cloudApiKey);
        }

        HttpResponse<java.io.InputStream> resp = client().send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("MinerU[" + mode + "] 下载结果失败 HTTP " + resp.statusCode());
        }

        byte[] bytes = resp.body().readAllBytes();

        // 尝试作为 ZIP 解压
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            StringBuilder foundFiles = new StringBuilder();
            String fullMd = null;
            String anyMd = null;
            // 已上传到 OSS 的图片文件名集合
            java.util.Set<String> uploadedImageNames = new java.util.HashSet<>();
            java.util.Set<String> imageExts = java.util.Set.of(
                    ".jpg", ".jpeg", ".png", ".gif", ".svg", ".webp", ".bmp");

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                foundFiles.append(name).append(", ");
                // 优先 full.md（MinerU 标准 Markdown 输出）
                if (name.equals("full.md") || name.endsWith("/full.md")) {
                    fullMd = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    continue;
                }
                // 备选：任意 .md 文件
                if (anyMd == null && (name.endsWith(".md") || name.endsWith("/markdown.md"))) {
                    anyMd = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    continue;
                }
                // 图片文件：上传到 OSS
                if (docId != null) {
                    String lower = name.toLowerCase();
                    for (String ext : imageExts) {
                        if (lower.endsWith(ext)) {
                            byte[] imgBytes = zis.readAllBytes();
                            // 取文件名部分（去掉目录前缀）
                            String imgName = name.contains("/")
                                    ? name.substring(name.lastIndexOf('/') + 1)
                                    : name;
                            // OSS 路径：{docId}/images/{filename}
                            String ossPath = docId + "/images/" + imgName;
                            try {
                                ossStorage.upload(ossPath,
                                        new java.io.ByteArrayInputStream(imgBytes), imgBytes.length);
                                uploadedImageNames.add(imgName);
                                log.debug("MinerU 图片上传 OSS: {} → {}", name, ossPath);
                            } catch (Exception e) {
                                log.warn("MinerU 图片上传 OSS 失败: {} -> {}", name, e.getMessage());
                            }
                            break;
                        }
                    }
                }
            }
            String md = fullMd != null ? fullMd : anyMd;
            if (md != null) {
                // 用正则替换 Markdown 中所有图片路径为代理 URL
                // 匹配 ![alt](任意路径/文件名.扩展名) 或 ![alt](文件名.扩展名)
                // 只替换已上传到 OSS 的图片文件名
                if (docId != null && !uploadedImageNames.isEmpty()) {
                    final String docIdFinal = docId;
                    java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile(
                            "!\\[[^\\]]*\\]\\(([^)]+)\\)");
                    java.util.regex.Matcher imgMatcher = imgPattern.matcher(md);
                    StringBuilder sb = new StringBuilder();
                    int replaced = 0;
                    while (imgMatcher.find()) {
                        String origPath = imgMatcher.group(1);
                        // 提取文件名（去掉路径前缀）
                        String fileName = origPath.contains("/")
                                ? origPath.substring(origPath.lastIndexOf('/') + 1)
                                : origPath;
                        // 去掉可能的 URL 参数（?xxx）
                        if (fileName.contains("?")) {
                            fileName = fileName.substring(0, fileName.indexOf('?'));
                        }
                        if (uploadedImageNames.contains(fileName)) {
                            String proxyUrl = "/api/doc/image/" + docIdFinal + "/" + fileName;
                            imgMatcher.appendReplacement(sb,
                                    "![](" + java.util.regex.Matcher.quoteReplacement(proxyUrl) + ")");
                            replaced++;
                        } else {
                            // 不是已知图片，保留原路径
                            imgMatcher.appendReplacement(sb,
                                    java.util.regex.Matcher.quoteReplacement(imgMatcher.group(0)));
                        }
                    }
                    imgMatcher.appendTail(sb);
                    md = sb.toString();
                    log.info("MinerU[{}] 图片处理: {} 张上传 OSS, {} 处路径已替换", mode,
                            uploadedImageNames.size(), replaced);
                }
                return md;
            }
            log.warn("MinerU[{}] ZIP 中未找到 .md 文件，包含: {}", mode,
                    foundFiles.length() > 0 ? foundFiles : "(空)");
        } catch (java.util.zip.ZipException e) {
            log.info("MinerU[{}] 结果非 ZIP 格式，尝试作为文本", mode);
        }

        // 尝试作为 JSON（可能含 markdown 字段）
        String text = new String(bytes, StandardCharsets.UTF_8);
        try {
            JsonNode json = mapper.readTree(text);
            String md = json.path("markdown").asText(
                    json.path("data").path("markdown").asText(""));
            if (!md.isBlank()) return md;
        } catch (Exception ignored) {
        }

        if (!text.isBlank()) return text;
        throw new RuntimeException("MinerU[" + mode + "] 无法从结果中提取 markdown 内容");
    }

    // ==================== 工具 ====================

    private String truncate(String s, int len) {
        if (s == null) return "";
        return s.length() > len ? s.substring(0, len) + "..." : s;
    }

    /** 任务句柄：区分 task_id（URL/local 模式）和 batch_id（cloud 上传模式） */
    public record TaskHandle(String id, boolean isBatch) {}

    /** MinerU 结果封装 */
    public record MineruResult(JsonNode contentList, String markdown, String resultUrl, String state, String errMsg) {
        public boolean hasContentList() {
            return contentList != null && contentList.isArray() && !contentList.isEmpty();
        }
        public boolean hasMarkdown() {
            return markdown != null && !markdown.isBlank();
        }
        public boolean hasResultUrl() {
            return resultUrl != null && !resultUrl.isBlank();
        }
    }
}
