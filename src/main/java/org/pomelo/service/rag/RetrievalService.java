package org.pomelo.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.milvus.MilvusVectorStore;
import org.pomelo.core.ollama.EmbeddingService;
import org.pomelo.core.rag.Bm25Indexer;
import org.pomelo.domain.doc.Document;
import org.pomelo.domain.kb.KnowledgeBase;
import org.pomelo.domain.rag.RetrievedChunk;
import org.pomelo.mapper.DocumentMapper;
import org.pomelo.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索服务（混合检索版）：
 * - 向量语义召回（正文 content + 摘要 summary + 关键词 keywords 三路）
 * - BM25 关键词召回
 * - RRF（Reciprocal Rank Fusion）融合排序
 * - 按知识库配置的 Top-K 和相似度阈值过滤
 * - 停用文档排除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final MilvusVectorStore vectorStore;
    private final KnowledgeBaseService kbService;
    private final DocumentMapper docRepository;
    private final Bm25Indexer bm25Indexer;

    /** RRF 融合参数：rank 的倒数权重 */
    private static final double RRF_K = 60.0;

    public List<RetrievedChunk> retrieve(String kbId, String query) {
        KnowledgeBase kb = kbService.getEntity(kbId);
        int topK = kb.getTopK();
        float[] qv = embeddingService.embedQuery(query);

        // 获取停用文档列表
        List<Document> allDocs = docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
        Set<String> disabledDocIds = allDocs.stream()
                .filter(d -> !d.isEnabled())
                .map(Document::getId)
                .collect(Collectors.toSet());
        List<String> disabledList = new ArrayList<>(disabledDocIds);

        // === 向量召回（三路） ===
        List<RetrievedChunk> contentHits = vectorStore.search(kbId, qv, topK, "content", disabledList);
        List<RetrievedChunk> summaryHits = new ArrayList<>();
        try {
            summaryHits = vectorStore.search(kbId, qv, topK / 2 + 1, "summary", disabledList);
        } catch (Exception e) {
            log.debug("摘要向量召回跳过: {}", e.getMessage());
        }
        List<RetrievedChunk> keywordHits = new ArrayList<>();
        try {
            keywordHits = vectorStore.search(kbId, qv, topK / 2 + 1, "keywords", disabledList);
        } catch (Exception e) {
            log.debug("关键词向量召回跳过: {}", e.getMessage());
        }

        // === BM25 关键词召回 ===
        List<Bm25Indexer.Bm25Hit> bm25Hits = new ArrayList<>();
        try {
            bm25Hits = bm25Indexer.search(kbId, query, topK * 2);
            // 过滤停用文档
            bm25Hits = bm25Hits.stream()
                    .filter(h -> !disabledDocIds.contains(h.docId()))
                    .toList();
        } catch (Exception e) {
            log.debug("BM25 召回跳过: {}", e.getMessage());
        }

        // === RRF 融合 ===
        Map<String, RrfEntry> fused = new HashMap<>();

        // 向量正文（权重 1.0）
        for (int i = 0; i < contentHits.size(); i++) {
            RetrievedChunk c = contentHits.get(i);
            String key = c.chunkId() != null ? c.chunkId() : "vec:" + c.docId() + ":" + c.chunkIndex();
            fused.computeIfAbsent(key, k -> new RrfEntry(c)).addScore(1.0 / (RRF_K + i + 1));
        }
        // 向量摘要（权重 0.5）
        for (int i = 0; i < summaryHits.size(); i++) {
            RetrievedChunk c = summaryHits.get(i);
            String key = "summary:" + c.docId();
            fused.computeIfAbsent(key, k -> new RrfEntry(c)).addScore(0.5 / (RRF_K + i + 1));
        }
        // 向量关键词（权重 0.5）
        for (int i = 0; i < keywordHits.size(); i++) {
            RetrievedChunk c = keywordHits.get(i);
            String key = "kw:" + c.docId();
            fused.computeIfAbsent(key, k -> new RrfEntry(c)).addScore(0.5 / (RRF_K + i + 1));
        }
        // BM25（权重 0.8）
        for (int i = 0; i < bm25Hits.size(); i++) {
            Bm25Indexer.Bm25Hit h = bm25Hits.get(i);
            String key = "bm25:" + h.chunkId();
            RetrievedChunk rc = RetrievedChunk.of(h.chunkId(), h.docId(), h.docName(),
                    kbId, h.page(), h.chunkIndex(), h.text(), h.score());
            fused.computeIfAbsent(key, k -> new RrfEntry(rc)).addScore(0.8 / (RRF_K + i + 1));
        }

        // 按阈值过滤 + 排序 + 截断
        double threshold = kb.getSimilarityThreshold();
        List<RetrievedChunk> result = fused.values().stream()
                .filter(e -> e.chunk.score() >= threshold || e.rrfScore > 0.01)
                .sorted(Comparator.comparingDouble(RrfEntry::getRrfScore).reversed())
                .limit(topK)
                .map(e -> RetrievedChunk.of(e.chunk.chunkId(), e.chunk.docId(), e.chunk.docName(),
                        e.chunk.kbId(), e.chunk.page(), e.chunk.chunkIndex(),
                        e.chunk.text(), (float) e.rrfScore))
                .collect(Collectors.toList());

        log.info("混合检索: query='{}' vec_content={} vec_summary={} vec_kw={} bm25={} 融合后={}",
                query, contentHits.size(), summaryHits.size(), keywordHits.size(),
                bm25Hits.size(), result.size());
        return result;
    }

    /**
     * 检索测试：可自定义 Top-K 和阈值，纯向量召回（不含 BM25）。
     */
    public List<RetrievedChunk> retrieveForTest(String kbId, String query, int topK, double threshold) {
        float[] qv = embeddingService.embedQuery(query);
        List<Document> allDocs = docRepository.findByKbIdOrderByCreatedAtDesc(kbId);
        List<String> disabledList = allDocs.stream()
                .filter(d -> !d.isEnabled())
                .map(Document::getId)
                .collect(Collectors.toList());
        List<RetrievedChunk> hits = vectorStore.search(kbId, qv, topK, "content", disabledList);
        return hits.stream()
                .filter(c -> c.score() >= threshold)
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .collect(Collectors.toList());
    }

    /** RRF 融合条目 */
    private static class RrfEntry {
        final RetrievedChunk chunk;
        double rrfScore = 0;

        RrfEntry(RetrievedChunk chunk) { this.chunk = chunk; }
        void addScore(double s) { rrfScore += s; }
        double getRrfScore() { return rrfScore; }
    }
}
