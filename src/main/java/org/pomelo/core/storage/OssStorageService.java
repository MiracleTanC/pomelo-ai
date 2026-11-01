package org.pomelo.core.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.PutObjectRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Date;

/**
 * 阿里云 OSS 对象存储服务：封装源文件的上传/下载/删除/预签名 URL。
 * <p>
 * OSS 公网可访问，MinerU 云端可直接通过预签名 URL 下载文件。
 * 对象路径规则：{kbId}/{timestamp}_{fileName}
 */
@Slf4j
@Service
public class OssStorageService {

    @Value("${pomelo.storage.oss.endpoint}")
    private String endpoint;

    @Value("${pomelo.storage.oss.access-key-id}")
    private String accessKeyId;

    @Value("${pomelo.storage.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${pomelo.storage.oss.bucket}")
    private String bucket;

    @Value("${pomelo.storage.oss.custom-domain:}")
    private String customDomain;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            if (ossClient.doesBucketExist(bucket)) {
                log.info("OSS bucket 已存在: {}", bucket);
            } else {
                ossClient.createBucket(bucket);
                log.info("OSS bucket 已创建: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("OSS 初始化检查失败（后续上传时会重试）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 上传文件到 OSS。
     *
     * @param objectPath  对象路径（不含 bucket），如 {kbId}/{timestamp}_{fileName}
     * @param inputStream 文件流
     * @param size        文件大小（OSS 通过流上传时可不用）
     * @return 对象路径
     */
    public String upload(String objectPath, InputStream inputStream, long size) {
        try {
            ossClient.putObject(new PutObjectRequest(bucket, objectPath, inputStream, null));
            log.debug("OSS 上传完成: {}/{}", bucket, objectPath);
            return objectPath;
        } catch (Exception e) {
            throw new RuntimeException("OSS 上传失败: " + objectPath, e);
        }
    }

    /**
     * 从 OSS 下载文件到临时文件（用于解析）。
     *
     * @param objectPath 对象路径
     * @return 临时文件路径（调用方负责删除）
     */
    public Path downloadToTemp(String objectPath) {
        try (InputStream is = ossClient.getObject(new GetObjectRequest(bucket, objectPath)).getObjectContent()) {
            String suffix = "";
            int dotIdx = objectPath.lastIndexOf('.');
            if (dotIdx >= 0) {
                suffix = objectPath.substring(dotIdx);
            }
            Path temp = Files.createTempFile("pomelo-", suffix);
            Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (Exception e) {
            throw new RuntimeException("OSS 下载失败: " + objectPath, e);
        }
    }

    /**
     * 删除 OSS 对象（不存在时静默忽略）。
     */
    public void delete(String objectPath) {
        try {
            ossClient.deleteObject(bucket, objectPath);
            log.debug("OSS 删除完成: {}/{}", bucket, objectPath);
        } catch (OSSException e) {
            if (!"NoSuchKey".equals(e.getErrorCode())) {
                log.warn("OSS 删除失败: {} -> {}", objectPath, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("OSS 删除失败: {} -> {}", objectPath, e.getMessage());
        }
    }

    /**
     * 构建 OSS 对象路径（不含原始文件名，文件名存在数据库 doc.name 中）。
     *
     * @param kbId    知识库 ID
     * @param fileName 原始文件名（仅用于提取扩展名）
     * @return {kbId}/{timestamp}{扩展名}
     */
    public static String buildObjectPath(String kbId, String fileName) {
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx >= 0) {
            ext = fileName.substring(dotIdx);
        }
        return kbId + "/" + System.currentTimeMillis() + ext;
    }

    /**
     * 生成预签名下载 URL（用于 MinerU 云端 API 下载文件）。
     * <p>
     * OSS 公网可访问，MinerU 云端可直接通过此 URL 下载文件。
     *
     * @param objectPath 对象路径
     * @param expirySeconds 过期时间（秒）
     * @return 可公网访问的下载 URL
     */
    public String getPresignedUrl(String objectPath, int expirySeconds) {
        try {
            Date expiration = new Date(System.currentTimeMillis() + expirySeconds * 1000L);
            URL url = ossClient.generatePresignedUrl(bucket, objectPath, expiration);
            // 如果配了自定义域名，替换 URL 中的 host
            String result = url.toString();
            if (customDomain != null && !customDomain.isBlank()) {
                result = result.replaceFirst("https?://[^/]+", customDomain.replaceAll("/$", ""));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("生成预签名 URL 失败: " + objectPath, e);
        }
    }
}
