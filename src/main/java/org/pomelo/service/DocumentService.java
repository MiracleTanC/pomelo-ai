package org.pomelo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.common.enums.DocStatus;
import org.pomelo.common.exception.BizException;
import org.pomelo.core.chunk.ChunkerDispatcher;
import org.pomelo.core.metadata.MetadataExtractor;
import org.pomelo.core.milvus.MilvusVectorStore;
import org.pomelo.core.ollama.EmbeddingService;
import org.pomelo.core.parser.ParserDispatcher;
import org.pomelo.domain.chunk.TextChunk;
import org.pomelo.domain.doc.DocChunk;
import org.pomelo.domain.doc.DocFolder;
import org.pomelo.domain.doc.DocMetadata;
import org.pomelo.domain.doc.DocTag;
import org.pomelo.domain.doc.Document;
import org.pomelo.domain.kb.KnowledgeBase;
import org.pomelo.domain.kb.KbStats;
import org.pomelo.domain.kb.MetaFieldConfig;
import org.pomelo.domain.parser.ParsedBlock;
import org.pomelo.domain.parser.ParsedDocument;
import org.pomelo.mapper.DocChunkMapper;
import org.pomelo.mapper.DocFolderMapper;
import org.pomelo.mapper.DocMetadataMapper;
import org.pomelo.mapper.DocTagMapper;
import org.pomelo.mapper.DocumentMapper;
import org.pomelo.mapper.MetaFieldConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文档服务：上传 → 解析 → 切片 → 向量化，四阶段解耦，可独立触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper docRepository;
    private final DocChunkMapper chunkRepository;
    private final DocMetadataMapper metadataRepository;
    private final MetaFieldConfigMapper metaFieldConfigRepository;
    private final DocFolderMapper folderRepository;
    private final DocTagMapper tagRepository;
    private final ParserDispatcher parser;
    private final ChunkerDispatcher chunkerDispatcher;
    private final EmbeddingService embeddingService;
    private final MilvusVectorStore vectorStore;
    private final KnowledgeBaseService kbService;
    private final MetadataExtractor metadataExtractor;
    private final org.pomelo.core.rag.Bm25Indexer bm25Indexer;

    /** 自注入代理对象，解决 @Async / @Transactional 自调用失效问题 */
    @Autowired
    @Lazy
    private DocumentService self;

    @Value("${pomelo.storage.upload-dir}")
    private String uploadDir;

    // ==================== 上传 ====================

    @Transactional
    public Document upload(String kbId, MultipartFile file) throws IOException {
        kbService.getEntity(kbId);
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new BizException("文件名不能为空");
        }

        Path dir = Paths.get(uploadDir).toAbsolutePath().resolve(kbId);
        Files.createDirectories(dir);
        String fileName = System.currentTimeMillis() + "_" + original;
        Path target = dir.resolve(fileName);
        file.transferTo(target);

        String sha256 = sha256(target);

        Document doc = Document.builder()
                .id(UUID.randomUUID().toString())
                .kbId(kbId)
                .name(original)
                .filePath(target.toString())
                .fileSize(file.getSize())
                .mimeType(file.getContentType())
                .sha256(sha256)
                .status(DocStatus.UPLOADED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        docRepository.insert(doc);

        // 若知识库开启自动向量化，异步触发完整流水线
        if (kbService.getEntity(kbId).isAutoVectorize()) {
            self.ingest(doc.getId());
        }
        return doc;
    }

    /** 批量上传 */
    @Transactional
    public List<Document> uploadBatch(String kbId, MultipartFile[] files) throws IOException {
        List<Document> docs = new ArrayList<>();
        for (MultipartFile f : files) {
            docs.add(upload(kbId, f));
        }
        return docs;
    }

    // ==================== 解析 ====================

    /** 解析单篇文档：提取纯文本 + 保存原文 + 提取元数据 */
    @Async("ingestExecutor")
    public void parse(String docId) {
        Document doc = docRepository.findById(docId);
        if (doc == null) return;
        try {
            updateStatus(doc, DocStatus.PARSING, null);
            ParsedDocument parsed = parser.parse(Path.of(doc.getFilePath()), doc.getName());

            // 保存解析结果纯文本
            String parsedContent = parsed.blocks().stream()
                    .map(b -> b.text())
                    .collect(Collectors.joining("\n\n"));
            doc.setParsedContent(parsedContent);
            doc.setParsedAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
            docRepository.update(doc);

            // 提取元数据（失败不阻断解析流程）
            try {
                self.extractAndSaveMetadata(doc);
            } catch (Exception e) {
                log.warn("元数据提取失败(不影响解析): {} -> {}", doc.getName(), e.getMessage());
            }

            updateStatus(doc, DocStatus.PARSED, null);
            log.info("文档解析完成: {}", doc.getName());
        } catch (Exception e) {
            log.error("文档解析失败: {}", doc.getName(), e);
            updateStatus(doc, DocStatus.PARSE_FAILED, truncate(e.getMessage(), 900));
        }
    }

    // ==================== 切片 ====================

    /** 切片单篇文档：按知识库切片参数切分 + 持久化切片记录 */
    @Async("ingestExecutor")
    public void chunk(String docId) {
        Document doc = docRepository.findById(docId);
        if (doc == null) return;
        if (doc.getParsedContent() == null || doc.getParsedContent().isBlank()) {
            throw new BizException("文档尚未解析，无法切片: " + docId);
        }
        try {
            updateStatus(doc, DocStatus.CHUNKING, null);

            // 重新构造 ParsedDocument
            var blocks = new ArrayList<org.pomelo.domain.parser.ParsedBlock>();
            String[] paragraphs = doc.getParsedContent().split("\n\n");
            for (int i = 0; i < paragraphs.length; i++) {
                String t = paragraphs[i].strip();
                if (!t.isBlank()) {
                    blocks.add(org.pomelo.domain.parser.ParsedBlock.of(t, 0));
                }
            }
            ParsedDocument parsed = ParsedDocument.of(blocks);

            // 切片
            List<TextChunk> chunks = doChunk(doc);
            if (chunks.isEmpty()) {
                throw new RuntimeException("文档内容为空，无可切分文本");
            }

            // 删除旧切片（若有）
            chunkRepository.deleteByDocId(docId);

            // 持久化切片
            List<DocChunk> docChunks = new ArrayList<>();
            for (TextChunk tc : chunks) {
                DocChunk dc = DocChunk.builder()
                        .id(UUID.randomUUID().toString())
                        .docId(docId)
                        .kbId(doc.getKbId())
                        .chunkIndex(tc.index())
                        .content(tc.text())
                        .page(tc.page())
                        .charCount(tc.text().length())
                        .contentHash(sha256String(tc.text()))
                        .status(DocChunk.ChunkStatus.PENDING)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                docChunks.add(dc);
            }
            chunkRepository.batchInsert(docChunks);

            doc.setChunkCount(chunks.size());
            doc.setVectorizedChunks(0);
            doc.setChunkedAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
            updateStatus(doc, DocStatus.CHUNKED, null);
            log.info("文档切片完成: {} -> {} 切片", doc.getName(), chunks.size());
        } catch (Exception e) {
            log.error("文档切片失败: {}", doc.getName(), e);
            updateStatus(doc, DocStatus.CHUNK_FAILED, truncate(e.getMessage(), 900));
        }
    }

    // ==================== 向量化 ====================

    /** 完整流水线：解析→切片→向量化（一步到位） */
    @Async("ingestExecutor")
    public void ingest(String docId) {
        Document doc = docRepository.findById(docId);
        if (doc == null) return;
        String kbId = doc.getKbId();
        try {
            // 1. 解析（若未解析）
            if (doc.getParsedContent() == null) {
                updateStatus(doc, DocStatus.PARSING, null);
                ParsedDocument parsed = parser.parse(Path.of(doc.getFilePath()), doc.getName());
                String parsedContent = parsed.blocks().stream()
                        .map(ParsedBlock::text).collect(Collectors.joining("\n\n"));
                doc.setParsedContent(parsedContent);
                doc.setParsedAt(Instant.now());
                docRepository.update(doc);
                try {
                    self.extractAndSaveMetadata(doc);
                } catch (Exception e) {
                    log.warn("元数据提取失败(不影响入库): {} -> {}", doc.getName(), e.getMessage());
                }
                updateStatus(doc, DocStatus.PARSED, null);
            }

            // 2. 切片（若未切片）
            if (doc.getChunkCount() == 0 || doc.getStatus() == DocStatus.PARSED) {
                updateStatus(doc, DocStatus.CHUNKING, null);
                List<TextChunk> chunks = doChunk(doc);
                persistChunks(doc, chunks);
                doc.setChunkedAt(Instant.now());
                updateStatus(doc, DocStatus.CHUNKED, null);
            }

            // 3. 向量化
            doVectorize(doc);

        } catch (Exception e) {
            log.error("文档入库失败: {}", doc.getName(), e);
            DocStatus failStatus = e.getMessage() != null && e.getMessage().contains("解析")
                    ? DocStatus.PARSE_FAILED : DocStatus.VECTOR_FAILED;
            updateStatus(doc, failStatus, truncate(e.getMessage(), 900));
        }
    }

    /** 向量化单篇文档（需已切片） */
    @Async("ingestExecutor")
    public void vectorize(String docId) {
        Document doc = docRepository.findById(docId);
        if (doc == null) return;
        try {
            doVectorize(doc);
        } catch (Exception e) {
            log.error("向量化失败: {}", doc.getName(), e);
            updateStatus(doc, DocStatus.VECTOR_FAILED, truncate(e.getMessage(), 900));
        }
    }

    /** 批量向量化：对多篇文档依次执行完整流水线 */
    public void batchVectorize(List<String> docIds) {
        for (String docId : docIds) {
            self.ingest(docId);
        }
        log.info("批量向量化任务已提交: {} 篇文档", docIds.size());
    }

    /** 向量化全部未完成文档 */
    public void vectorizeAll(String kbId) {
        List<DocStatus> pending = List.of(
                DocStatus.UPLOADED, DocStatus.PARSED, DocStatus.CHUNKED,
                DocStatus.PARSE_FAILED, DocStatus.CHUNK_FAILED, DocStatus.VECTOR_FAILED);
        List<Document> docs = docRepository.findByKbIdAndStatusIn(kbId, pending);
        for (Document doc : docs) {
            self.ingest(doc.getId());
        }
        log.info("全库向量化任务已提交: {} 篇文档", docs.size());
    }

    // ==================== 查询 ====================

    public List<Document> list(String kbId) {
        return docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }

    public List<Document> listByStatus(String kbId, List<DocStatus> statuses) {
        return docRepository.findByKbIdAndStatusIn(kbId, statuses);
    }

    public Document getEntity(String docId) {
        Document doc = docRepository.findById(docId);
        if (doc == null) throw new BizException("文档不存在: " + docId);
        return doc;
    }

    public DocDetail getDetail(String docId) {
        Document doc = getEntity(docId);
        List<DocChunk> chunks = chunkRepository.findByDocIdOrderByChunkIndexAsc(docId);
        List<DocMetadata> metadata = metadataRepository.findByDocId(docId);
        return new DocDetail(doc, chunks, metadata);
    }

    // ==================== 删除 ====================

    @Transactional
    public void delete(String docId) {
        Document doc = getEntity(docId);
        // 删 Milvus 向量
        if (doc.getStatus() == DocStatus.INDEXED) {
            vectorStore.deleteByDoc(doc.getKbId(), docId);
        }
        // 删 BM25 索引
        List<DocChunk> chunks = chunkRepository.findByDocId(docId);
        for (DocChunk c : chunks) bm25Indexer.removeChunk(doc.getKbId(), c.getId());
        // 删切片
        chunkRepository.deleteByDocId(docId);
        // 删元数据
        metadataRepository.deleteByDocId(docId);
        // 删标签
        tagRepository.deleteByDocId(docId);
        // 删本地文件
        try {
            if (doc.getFilePath() != null) {
                Files.deleteIfExists(Path.of(doc.getFilePath()));
            }
        } catch (IOException e) {
            log.warn("删除本地文件失败: {}", doc.getFilePath());
        }
        // 删文档记录
        docRepository.deleteById(doc.getId());
        log.info("文档已删除: {}", doc.getName());
    }

    // ==================== 重新解析 ====================

    /** 重新解析：清除旧结果，重新走完整流水线（解析→切片→向量化） */
    @Async("ingestExecutor")
    public void reParse(String docId) {
        Document doc = getEntity(docId);
        // 若已索引，先删 Milvus 向量
        if (doc.getStatus() == DocStatus.INDEXED) {
            vectorStore.deleteByDoc(doc.getKbId(), docId);
        }
        // 删旧切片
        chunkRepository.deleteByDocId(docId);
        // 清空旧结果，重置为上传状态
        doc.setParsedContent(null);
        doc.setChunkCount(0);
        doc.setVectorizedChunks(0);
        doc.setParsedAt(null);
        doc.setChunkedAt(null);
        doc.setVectorizedAt(null);
        updateStatus(doc, DocStatus.UPLOADED, null);
        // 走完整流水线（通过代理对象调用，确保 @Async 生效）
        self.ingest(docId);
    }

    // ==================== 切片编辑 + 增量向量化 ====================

    /**
     * 预览切片：返回文档当前切片列表（不持久化，用于切片参数调整前预览）。
     * 若文档已切片，返回已持久化的切片；若已解析未切片，则临时切片返回。
     */
    public List<DocChunk> previewChunks(String docId) {
        Document doc = getEntity(docId);
        List<DocChunk> existing = chunkRepository.findByDocIdOrderByChunkIndexAsc(docId);
        if (!existing.isEmpty()) {
            return existing;
        }
        // 未切片但有解析内容，临时切分预览
        if (doc.getParsedContent() == null || doc.getParsedContent().isBlank()) {
            return List.of();
        }
        List<TextChunk> temp = doChunk(doc);
        List<DocChunk> preview = new ArrayList<>();
        for (TextChunk tc : temp) {
            preview.add(DocChunk.builder()
                    .docId(docId).kbId(doc.getKbId())
                    .chunkIndex(tc.index()).content(tc.text())
                    .page(tc.page()).charCount(tc.text().length())
                    .status(DocChunk.ChunkStatus.PENDING)
                    .build());
        }
        return preview;
    }

    /**
     * 编辑切片内容：标记为 EDITED，需重新向量化。
     */
    @Transactional
    public DocChunk editChunk(String chunkId, String newContent) {
        DocChunk chunk = chunkRepository.findById(chunkId);
        if (chunk == null) throw new BizException("切片不存在: " + chunkId);
        String oldHash = chunk.getContentHash();
        chunk.setContent(newContent);
        chunk.setCharCount(newContent != null ? newContent.length() : 0);
        chunk.setContentHash(sha256String(newContent));
        chunk.setStatus(DocChunk.ChunkStatus.EDITED);
        chunk.setUpdatedAt(Instant.now());
        chunkRepository.update(chunk);

        // 若该切片已在 Milvus，删除旧向量
        vectorStore.deleteByChunkId(chunk.getKbId(), chunkId);
        // 增量更新 BM25 索引
        bm25Indexer.updateChunk(chunk.getKbId(), chunk);
        log.info("切片已编辑，旧向量已删除: {} (hash {} -> {})", chunkId, oldHash, chunk.getContentHash());
        return chunk;
    }

    /**
     * 增量向量化：只重新嵌入 EDITED/FAILED 状态的切片，不影响已向量化的切片。
     */
    @Async("ingestExecutor")
    public void incrementalVectorize(String docId) {
        Document doc = getEntity(docId);
        List<DocChunk> chunks = chunkRepository.findByDocIdOrderByChunkIndexAsc(docId);

        List<DocChunk> dirty = chunks.stream()
                .filter(c -> c.getStatus() == DocChunk.ChunkStatus.EDITED
                        || c.getStatus() == DocChunk.ChunkStatus.FAILED
                        || c.getStatus() == DocChunk.ChunkStatus.PENDING)
                .toList();

        if (dirty.isEmpty()) {
            log.info("文档 {} 无需增量向量化（所有切片已向量化的）", doc.getName());
            return;
        }

        try {
            updateStatus(doc, DocStatus.VECTORIZING, null);

            List<String> chunkIds = dirty.stream().map(DocChunk::getId).toList();
            List<TextChunk> textChunks = dirty.stream()
                    .map(c -> TextChunk.of(c.getContent(), c.getPage(), c.getChunkIndex()))
                    .toList();
            List<float[]> vectors = embeddingService.embedBatch(textChunks);

            vectorStore.insertWithChunkIds(doc.getKbId(), doc.getId(), doc.getName(),
                    chunkIds, textChunks, vectors);

            // 更新切片状态（已存在的切片，逐条 update）
            for (DocChunk c : dirty) {
                c.setStatus(DocChunk.ChunkStatus.VECTORIZED);
                c.setUpdatedAt(Instant.now());
                chunkRepository.update(c);
            }

            // 更新文档进度
            long vectorized = chunkRepository.countByDocIdAndStatus(docId, DocChunk.ChunkStatus.VECTORIZED);
            doc.setVectorizedChunks((int) vectorized);
            if (vectorized == chunks.size()) {
                doc.setStatus(DocStatus.INDEXED);
                doc.setVectorizedAt(Instant.now());
            }
            doc.setUpdatedAt(Instant.now());
            docRepository.update(doc);

            log.info("增量向量化完成: {} -> {} 切片重新嵌入", doc.getName(), dirty.size());
        } catch (Exception e) {
            log.error("增量向量化失败: {}", doc.getName(), e);
            updateStatus(doc, DocStatus.VECTOR_FAILED, truncate(e.getMessage(), 900));
        }
    }

    /**
     * 向量化元数据（摘要/关键词）：对 vectorized=true 的元数据字段生成独立向量入 Milvus。
     */
    @Async("ingestExecutor")
    public void vectorizeMetadata(String docId) {
        Document doc = getEntity(docId);
        List<DocMetadata> metadata = metadataRepository.findByDocId(docId);
        if (metadata.isEmpty()) return;

        // 读取知识库字段配置，找出需要向量化的字段
        List<MetaFieldConfig> configs = metaFieldConfigRepository
                .findByKbIdAndEnabledTrueOrderBySortOrderAsc(doc.getKbId());
        Set<String> vectorizedKeys = configs.stream()
                .filter(MetaFieldConfig::isVectorized)
                .map(MetaFieldConfig::getFieldKey)
                .collect(java.util.stream.Collectors.toSet());

        for (DocMetadata m : metadata) {
            if (!vectorizedKeys.contains(m.getFieldKey())) continue;
            String value = m.getFieldValue();
            if (value == null || value.isBlank()) continue;

            // 先删旧的
            vectorStore.deleteByDocAndType(doc.getKbId(), docId, m.getFieldKey());
            // 嵌入并插入
            float[] vec = embeddingService.embedQuery(value);
            vectorStore.insertMetadataVector(doc.getKbId(), docId, doc.getName(),
                    m.getId(), m.getFieldKey(), value, vec);
            log.info("元数据向量化: {} / {} -> {}", doc.getName(), m.getFieldKey(), value.length());
        }
    }

    // ==================== 内部方法 ====================

    private void doVectorize(Document doc) {
        updateStatus(doc, DocStatus.VECTORIZING, null);

        List<DocChunk> chunks = chunkRepository.findByDocIdOrderByChunkIndexAsc(doc.getId());
        List<DocChunk> pending = chunks.stream()
                .filter(c -> c.getStatus() == DocChunk.ChunkStatus.PENDING
                        || c.getStatus() == DocChunk.ChunkStatus.EDITED
                        || c.getStatus() == DocChunk.ChunkStatus.FAILED)
                .toList();

        if (pending.isEmpty()) {
            doc.setStatus(DocStatus.INDEXED);
            doc.setVectorizedAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
            docRepository.update(doc);
            return;
        }

        // 批量嵌入
        List<String> chunkIds = pending.stream().map(DocChunk::getId).toList();
        List<TextChunk> textChunks = pending.stream()
                .map(c -> TextChunk.of(c.getContent(), c.getPage(), c.getChunkIndex()))
                .toList();
        List<float[]> vectors = embeddingService.embedBatch(textChunks);

        // 入 Milvus（带 chunkId 支持增量删除）
        vectorStore.insertWithChunkIds(doc.getKbId(), doc.getId(), doc.getName(),
                chunkIds, textChunks, vectors);

        // 更新切片状态（已存在的切片，逐条 update）
        for (DocChunk c : pending) {
            c.setStatus(DocChunk.ChunkStatus.VECTORIZED);
            c.setUpdatedAt(Instant.now());
            chunkRepository.update(c);
        }

        doc.setVectorizedChunks(chunks.size());
        doc.setStatus(DocStatus.INDEXED);
        doc.setVectorizedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        docRepository.update(doc);

        log.info("向量化完成: {} -> {} 切片", doc.getName(), pending.size());

        // 向量化元数据（摘要/关键词）
        try {
            vectorizeMetadata(doc.getId());
        } catch (Exception e) {
            log.warn("元数据向量化失败(不影响主流程): {}", e.getMessage());
        }

        // 重建 BM25 索引
        try {
            bm25Indexer.rebuild(doc.getKbId());
        } catch (Exception e) {
            log.warn("BM25 索引重建失败(不影响主流程): {}", e.getMessage());
        }
    }

    private List<TextChunk> doChunk(Document doc) {
        var blocks = new ArrayList<org.pomelo.domain.parser.ParsedBlock>();
        String[] paragraphs = doc.getParsedContent().split("\n\n");
        for (String p : paragraphs) {
            String t = p.strip();
            if (!t.isBlank()) {
                blocks.add(org.pomelo.domain.parser.ParsedBlock.of(t, 0));
            }
        }
        KnowledgeBase kb = kbService.getEntity(doc.getKbId());
        return chunkerDispatcher.chunk(ParsedDocument.of(blocks),
                kb.getChunkStrategy(), kb.getChunkSize(), kb.getChunkOverlap());
    }

    private void persistChunks(Document doc, List<TextChunk> chunks) {
        chunkRepository.deleteByDocId(doc.getId());
        List<DocChunk> docChunks = new ArrayList<>();
        for (TextChunk tc : chunks) {
            docChunks.add(DocChunk.builder()
                    .id(UUID.randomUUID().toString())
                    .docId(doc.getId())
                    .kbId(doc.getKbId())
                    .chunkIndex(tc.index())
                    .content(tc.text())
                    .page(tc.page())
                    .charCount(tc.text().length())
                    .contentHash(sha256String(tc.text()))
                    .status(DocChunk.ChunkStatus.PENDING)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
        }
        chunkRepository.batchInsert(docChunks);
        doc.setChunkCount(chunks.size());
        doc.setVectorizedChunks(0);
    }

    @Transactional
    public void extractAndSaveMetadata(Document doc) {
        List<MetaFieldConfig> configs = metaFieldConfigRepository
                .findByKbIdAndEnabledTrueOrderBySortOrderAsc(doc.getKbId());
        if (configs.isEmpty()) return;
        metadataRepository.deleteByDocId(doc.getId());
        List<DocMetadata> metadata = metadataExtractor.extract(doc, configs);
        if (!metadata.isEmpty()) {
            for (DocMetadata m : metadata) {
                m.setId(UUID.randomUUID().toString());
            }
            metadataRepository.batchInsert(metadata);
        }
        log.info("元数据提取完成: {} -> {} 字段", doc.getName(), metadata.size());
    }

    // ==================== 内部方法 ====================

    /**
     * 更新文档状态（单条 UPDATE，天然原子，无需 @Transactional）。
     * 直接调用即可，不经过代理。
     */
    public void updateStatus(Document doc, DocStatus status, String errorMsg) {
        doc.setStatus(status);
        doc.setErrorMsg(errorMsg);
        doc.setUpdatedAt(Instant.now());
        docRepository.update(doc);
    }

    private String truncate(String s, int len) {
        return s == null ? null : (s.length() > len ? s.substring(0, len) : s);
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IOException("计算文件哈希失败", e);
        }
    }

    private String sha256String(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes()));
        } catch (Exception e) {
            return null;
        }
    }

    /** 文档详情聚合对象 */
    public record DocDetail(Document document, List<DocChunk> chunks, List<DocMetadata> metadata) {
    }

    // ==================== 文档启用/停用 ====================

    @Transactional
    public void setEnabled(String docId, boolean enabled) {
        Document doc = getEntity(docId);
        doc.setEnabled(enabled);
        doc.setUpdatedAt(Instant.now());
        docRepository.update(doc);
        log.info("文档 {} 已{}", doc.getName(), enabled ? "启用" : "停用");
    }

    // ==================== 文件夹管理 ====================

    @Transactional
    public DocFolder createFolder(String kbId, String name, String parentId) {
        kbService.getEntity(kbId);
        DocFolder folder = DocFolder.builder()
                .id(UUID.randomUUID().toString())
                .kbId(kbId).name(name).parentId(parentId)
                .createdAt(Instant.now()).build();
        folderRepository.insert(folder);
        return folder;
    }

    public List<DocFolder> listFolders(String kbId) {
        return folderRepository.findByKbIdOrderBySortOrderAsc(kbId);
    }

    @Transactional
    public void deleteFolder(String folderId) {
        folderRepository.deleteById(folderId);
    }

    @Transactional
    public void moveDocToFolder(String docId, String folderId) {
        Document doc = getEntity(docId);
        doc.setFolderId(folderId);
        doc.setUpdatedAt(Instant.now());
        docRepository.update(doc);
    }

    // ==================== 标签管理 ====================

    @Transactional
    public void addTags(String docId, List<String> tags) {
        Document doc = getEntity(docId);
        for (String tag : tags) {
            DocTag t = DocTag.builder()
                    .id(UUID.randomUUID().toString())
                    .kbId(doc.getKbId()).docId(docId).tag(tag)
                    .createdAt(Instant.now()).build();
            tagRepository.insert(t);
        }
    }

    public List<DocTag> getTags(String docId) {
        return tagRepository.findByDocId(docId);
    }

    @Transactional
    public void removeTag(String tagId) {
        tagRepository.deleteById(tagId);
    }

    // ==================== 元数据批量重新提取 ====================

    /**
     * 对知识库全部文档按当前字段配置重新提取元数据（新增字段后补充提取）。
     */
    @Async("ingestExecutor")
    public void reExtractMetadata(String kbId) {
        List<Document> docs = docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
        int count = 0;
        for (Document doc : docs) {
            if (doc.getParsedContent() == null || doc.getParsedContent().isBlank()) continue;
            try {
                self.extractAndSaveMetadata(doc);
                count++;
            } catch (Exception e) {
                log.warn("元数据提取失败: {} -> {}", doc.getName(), e.getMessage());
            }
        }
        log.info("元数据批量重新提取完成: kbId={} -> {} 篇文档", kbId, count);
    }

    // ==================== 统计监控 ====================

    public KbStats getStats(String kbId) {
        KbStats stats = new KbStats();
        KnowledgeBase kb = kbService.getEntity(kbId);
        stats.setChunkSize(kb.getChunkSize());
        stats.setTopK(kb.getTopK());
        stats.setAutoVectorize(kb.isAutoVectorize());
        stats.setChunkStrategy(kb.getChunkStrategy());

        // 实时从 doc 表聚合
        int totalDocs = docRepository.countByKbId(kbId);
        int totalChunks = docRepository.sumChunkCountByKbId(kbId);
        int totalVectorized = docRepository.sumVectorizedChunksByKbId(kbId);
        stats.setDocCount(totalDocs);
        stats.setChunkCount(totalChunks);
        stats.setTotalDocs(totalDocs);
        stats.setTotalChunks(totalChunks);
        stats.setTotalVectorized(totalVectorized);

        int indexed = 0, pending = 0, failed = 0;
        List<Document> docs = docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
        for (Document d : docs) {
            if (d.getStatus() == DocStatus.INDEXED) indexed++;
            else if (d.getStatus().isTerminal()) failed++;
            else if (d.getStatus().isProcessing() || d.getStatus() == DocStatus.UPLOADED
                    || d.getStatus() == DocStatus.PARSED || d.getStatus() == DocStatus.CHUNKED) pending++;
        }
        stats.setIndexedDocs(indexed);
        stats.setPendingDocs(pending);
        stats.setFailedDocs(failed);

        // 元数据字段数
        List<MetaFieldConfig> configs = metaFieldConfigRepository.findByKbIdOrderBySortOrderAsc(kbId);
        stats.setMetaFieldCount(configs.size());

        return stats;
    }
}
