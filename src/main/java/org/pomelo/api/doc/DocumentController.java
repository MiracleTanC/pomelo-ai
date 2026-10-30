package org.pomelo.api.doc;

import lombok.RequiredArgsConstructor;
import org.pomelo.api.doc.vo.DocChunkVo;
import org.pomelo.api.doc.vo.DocDetailVo;
import org.pomelo.api.doc.vo.DocMetadataVo;
import org.pomelo.api.doc.vo.DocVo;
import org.pomelo.common.api.Result;
import org.pomelo.common.enums.DocStatus;
import org.pomelo.domain.doc.DocChunk;
import org.pomelo.domain.doc.DocMetadata;
import org.pomelo.domain.doc.Document;
import org.pomelo.service.DocumentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/doc")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService docService;

    /** 单文件上传 */
    @PostMapping("/upload/{kbId}")
    public Result<DocVo> upload(@PathVariable String kbId,
                                @RequestParam("file") MultipartFile file) throws IOException {
        Document doc = docService.upload(kbId, file);
        return Result.ok(toVo(doc));
    }

    /** 批量上传 */
    @PostMapping("/upload-batch/{kbId}")
    public Result<List<DocVo>> uploadBatch(@PathVariable String kbId,
                                           @RequestParam("files") MultipartFile[] files) throws IOException {
        List<Document> docs = docService.uploadBatch(kbId, files);
        return Result.ok(docs.stream().map(this::toVo).toList());
    }

    /** 文档列表 */
    @GetMapping("/list/{kbId}")
    public Result<List<DocVo>> list(@PathVariable String kbId,
                                    @RequestParam(required = false) String status) {
        List<Document> docs;
        if (status != null && !status.isBlank()) {
            List<DocStatus> statuses = List.of(DocStatus.valueOf(status));
            docs = docService.listByStatus(kbId, statuses);
        } else {
            docs = docService.list(kbId);
        }
        return Result.ok(docs.stream().map(this::toVo).toList());
    }

    /** 文档详情（含原文、切片、元数据） */
    @GetMapping("/{docId}")
    public Result<DocDetailVo> detail(@PathVariable String docId) {
        DocumentService.DocDetail detail = docService.getDetail(docId);
        return Result.ok(toDetailVo(detail));
    }

    /** 解析文档（仅解析，不切片/向量化） */
    @PostMapping("/parse/{docId}")
    public Result<Void> parse(@PathVariable String docId) {
        docService.parse(docId);
        return Result.ok();
    }

    /** 切片文档（仅切片，不向量化） */
    @PostMapping("/chunk/{docId}")
    public Result<Void> chunk(@PathVariable String docId) {
        docService.chunk(docId);
        return Result.ok();
    }

    /** 向量化单篇文档（需已切片） */
    @PostMapping("/vectorize/{docId}")
    public Result<Void> vectorize(@PathVariable String docId) {
        docService.vectorize(docId);
        return Result.ok();
    }

    /** 完整流水线：解析→切片→向量化（一步到位） */
    @PostMapping("/ingest/{docId}")
    public Result<Void> ingest(@PathVariable String docId) {
        docService.ingest(docId);
        return Result.ok();
    }

    /** 批量向量化 */
    @PostMapping("/vectorize-batch")
    public Result<Void> batchVectorize(@RequestBody BatchVectorizeRequest req) {
        docService.batchVectorize(req.docIds());
        return Result.ok();
    }

    /** 全库向量化 */
    @PostMapping("/vectorize-all/{kbId}")
    public Result<Void> vectorizeAll(@PathVariable String kbId) {
        docService.vectorizeAll(kbId);
        return Result.ok();
    }

    /** 重新解析 */
    @PostMapping("/reparse/{docId}")
    public Result<Void> reParse(@PathVariable String docId) {
        docService.reParse(docId);
        return Result.ok();
    }

    /** 切片预览（未切片时临时切分预览，不持久化） */
    @GetMapping("/chunks/preview/{docId}")
    public Result<List<DocChunkVo>> previewChunks(@PathVariable String docId) {
        return Result.ok(docService.previewChunks(docId).stream()
                .map(c -> new DocChunkVo(c.getId(), c.getDocId(), c.getChunkIndex(),
                        c.getContent(), c.getPage(), c.getCharCount(), c.getContentHash(),
                        c.getStatus().name(), c.getCreatedAt(), c.getUpdatedAt()))
                .toList());
    }

    /** 编辑切片内容（标记 EDITED，自动删旧向量） */
    @PutMapping("/chunk/{chunkId}")
    public Result<DocChunkVo> editChunk(@PathVariable String chunkId,
                                        @RequestBody EditChunkRequest req) {
        var c = docService.editChunk(chunkId, req.content());
        return Result.ok(new DocChunkVo(c.getId(), c.getDocId(), c.getChunkIndex(),
                c.getContent(), c.getPage(), c.getCharCount(), c.getContentHash(),
                c.getStatus().name(), c.getCreatedAt(), c.getUpdatedAt()));
    }

    /** 增量向量化（只重嵌入 EDITED/FAILED 切片） */
    @PostMapping("/vectorize-incremental/{docId}")
    public Result<Void> incrementalVectorize(@PathVariable String docId) {
        docService.incrementalVectorize(docId);
        return Result.ok();
    }

    /** 删除文档（同步删向量+切片+元数据+文件） */
    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable String docId) {
        docService.delete(docId);
        return Result.ok();
    }

    // ==================== VO 转换 ====================

    private DocVo toVo(Document d) {
        return new DocVo(d.getId(), d.getKbId(), d.getName(), d.getFileSize(),
                d.getMimeType(), d.getSha256(), d.getStatus(), d.getChunkCount(),
                d.getVectorizedChunks(), d.getErrorMsg(), d.isEnabled(), d.getFolderId(),
                d.getCreatedAt(), d.getUpdatedAt(),
                d.getParsedAt(), d.getChunkedAt(), d.getVectorizedAt());
    }

    private DocDetailVo toDetailVo(DocumentService.DocDetail detail) {
        Document d = detail.document();
        return new DocDetailVo(
                d.getId(), d.getKbId(), d.getName(), d.getFileSize(),
                d.getMimeType(), d.getSha256(), d.getStatus(), d.getChunkCount(),
                d.getVectorizedChunks(), d.getErrorMsg(), d.isEnabled(), d.getFolderId(),
                d.getCreatedAt(), d.getUpdatedAt(), d.getParsedAt(), d.getChunkedAt(), d.getVectorizedAt(),
                d.getParsedContent(),
                detail.chunks().stream().map(this::toChunkVo).toList(),
                detail.metadata().stream().map(this::toMetadataVo).toList()
        );
    }

    private DocChunkVo toChunkVo(DocChunk c) {
        return new DocChunkVo(c.getId(), c.getDocId(), c.getChunkIndex(),
                c.getContent(), c.getPage(), c.getCharCount(), c.getContentHash(),
                c.getStatus().name(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private DocMetadataVo toMetadataVo(DocMetadata m) {
        return new DocMetadataVo(m.getFieldKey(), m.getFieldName(), m.getFieldValue());
    }

    public record BatchVectorizeRequest(List<String> docIds) {
    }

    public record EditChunkRequest(String content) {
    }

    // ---- 文档启用/停用 ----
    @PutMapping("/{docId}/enabled")
    public Result<Void> setEnabled(@PathVariable String docId, @RequestParam boolean enabled) {
        docService.setEnabled(docId, enabled);
        return Result.ok();
    }

    // ---- 文件夹管理 ----
    @PostMapping("/folder/{kbId}")
    public Result<Void> createFolder(@PathVariable String kbId, @RequestParam String name,
                                     @RequestParam(required = false) String parentId) {
        docService.createFolder(kbId, name, parentId);
        return Result.ok();
    }

    @GetMapping("/folder/{kbId}")
    public Result<List<org.pomelo.domain.doc.DocFolder>> listFolders(@PathVariable String kbId) {
        return Result.ok(docService.listFolders(kbId));
    }

    @DeleteMapping("/folder/{folderId}")
    public Result<Void> deleteFolder(@PathVariable String folderId) {
        docService.deleteFolder(folderId);
        return Result.ok();
    }

    @PutMapping("/{docId}/folder")
    public Result<Void> moveDocToFolder(@PathVariable String docId, @RequestParam String folderId) {
        docService.moveDocToFolder(docId, folderId);
        return Result.ok();
    }

    // ---- 标签管理 ----
    @PostMapping("/{docId}/tags")
    public Result<Void> addTags(@PathVariable String docId, @RequestBody List<String> tags) {
        docService.addTags(docId, tags);
        return Result.ok();
    }

    @GetMapping("/{docId}/tags")
    public Result<List<org.pomelo.domain.doc.DocTag>> getTags(@PathVariable String docId) {
        return Result.ok(docService.getTags(docId));
    }

    @DeleteMapping("/tag/{tagId}")
    public Result<Void> removeTag(@PathVariable String tagId) {
        docService.removeTag(tagId);
        return Result.ok();
    }

    // ---- 元数据批量重新提取 ----
    @PostMapping("/meta-reextract/{kbId}")
    public Result<Void> reExtractMetadata(@PathVariable String kbId) {
        docService.reExtractMetadata(kbId);
        return Result.ok();
    }

    // ---- 统计监控 ----
    @GetMapping("/stats/{kbId}")
    public Result<DocumentService.KbStats> getStats(@PathVariable String kbId) {
        return Result.ok(docService.getStats(kbId));
    }
}
