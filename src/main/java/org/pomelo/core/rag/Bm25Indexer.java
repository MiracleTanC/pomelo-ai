package org.pomelo.core.rag;

import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.doc.DocChunk;
import org.pomelo.mapper.DocChunkMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25 关键词检索引擎：内存倒排索引，按知识库隔离。
 * <p>
 * 对知识库中所有已向量化的切片建立 BM25 索引，支持关键词检索召回。
 * 与向量检索结果通过 RRF（Reciprocal Rank Fusion）融合。
 * <p>
 * 索引在首次查询时懒加载，之后增量更新（切片编辑/新增时）。
 */
@Slf4j
@Component
public class Bm25Indexer {

    private final DocChunkMapper chunkRepo;

    /** 每个知识库的 BM25 索引：kbId → Bm25Index */
    private final Map<String, Bm25Index> indexes = new ConcurrentHashMap<>();

    /** BM25 参数 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private static final Pattern TOKENIZER = Pattern.compile("[\\s，。！？、；：,.!?;:\\n\\r]+");

    public Bm25Indexer(DocChunkMapper chunkRepo) {
        this.chunkRepo = chunkRepo;
    }

    /**
     * 检索 Top-K 关键词匹配结果。
     */
    public List<Bm25Hit> search(String kbId, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        Bm25Index index = indexes.computeIfAbsent(kbId, this::buildIndex);
        if (index.isEmpty()) return List.of();

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return List.of();

        List<Bm25Hit> hits = new ArrayList<>();
        for (Map.Entry<String, DocInfo> entry : index.docs.entrySet()) {
            double score = 0;
            DocInfo doc = entry.getValue();
            for (String term : queryTerms) {
                Integer tf = doc.termFreq.get(term);
                if (tf == null) continue;
                // BM25 公式
                double idf = index.idf(term);
                double denom = tf + K1 * (1 - B + B * doc.length / index.getAvgDocLength());
                score += idf * (tf * (K1 + 1)) / denom;
            }
            if (score > 0) {
                hits.add(new Bm25Hit(entry.getKey(), doc.milvusId, doc.docId, doc.docName,
                        doc.page, doc.chunkIndex, doc.text, (float) score));
            }
        }
        hits.sort(Comparator.comparingDouble(Bm25Hit::score).reversed());
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    /**
     * 增量更新单个切片（编辑后调用）。
     */
    public void updateChunk(String kbId, DocChunk chunk) {
        Bm25Index index = indexes.get(kbId);
        if (index == null) return; // 尚未懒加载，下次查询会全量构建
        index.remove(chunk.getId());
        index.add(chunk.getId(), chunk.getDocId(), getDocName(chunk), chunk.getPage(),
                chunk.getChunkIndex(), chunk.getContent());
        log.debug("BM25 增量更新: kb={} chunk={}", kbId, chunk.getId());
    }

    /**
     * 删除切片索引（文档删除时调用）。
     */
    public void removeChunk(String kbId, String chunkId) {
        Bm25Index index = indexes.get(kbId);
        if (index != null) index.remove(chunkId);
    }

    /**
     * 重建知识库索引。
     */
    public void rebuild(String kbId) {
        indexes.put(kbId, buildIndex(kbId));
        log.info("BM25 索引重建: kb={} -> {} 切片", kbId, indexes.get(kbId).size());
    }

    /**
     * 清除索引（知识库删除时）。
     */
    public void clear(String kbId) {
        indexes.remove(kbId);
    }

    private Bm25Index buildIndex(String kbId) {
        Bm25Index index = new Bm25Index();
        List<DocChunk> chunks = chunkRepo.findByKbId(kbId);
        for (DocChunk c : chunks) {
            if (c.getStatus() != DocChunk.ChunkStatus.VECTORIZED) continue;
            index.add(c.getId(), c.getDocId(), getDocName(c), c.getPage(),
                    c.getChunkIndex(), c.getContent());
        }
        log.info("BM25 索引构建: kb={} -> {} 切片", kbId, index.size());
        return index;
    }

    private String getDocName(DocChunk c) {
        // DocChunk 没有 docName 字段，用 docId 简写
        return c.getDocId().length() > 8 ? c.getDocId().substring(0, 8) : c.getDocId();
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        // 对中文做单字 unigram + 英文按空格分词
        List<String> tokens = new ArrayList<>();
        String[] parts = TOKENIZER.split(text);
        for (String part : parts) {
            String t = part.strip().toLowerCase();
            if (t.isBlank()) continue;
            if (isMostlyAscii(t)) {
                // 英文直接加
                tokens.add(t);
            } else {
                // 中文拆成 bigram
                for (int i = 0; i < t.length() - 1; i++) {
                    tokens.add(t.substring(i, i + 2));
                }
                if (t.length() == 1) tokens.add(t);
            }
        }
        return tokens;
    }

    private boolean isMostlyAscii(String s) {
        int ascii = 0;
        for (char c : s.toCharArray()) if (c < 128) ascii++;
        return ascii > s.length() / 2;
    }

    // ---- 内部数据结构 ----

    private static class Bm25Index {
        final Map<String, DocInfo> docs = new HashMap<>();
        final Map<String, Integer> docFreq = new HashMap<>(); // term → 包含该 term 的文档数
        long totalLength = 0;

        void add(String milvusId, String docId, String docName, int page,
                 int chunkIndex, String text) {
            List<String> terms = tokenizeStatic(text);
            Map<String, Integer> tf = new HashMap<>();
            for (String term : terms) tf.merge(term, 1, Integer::sum);
            DocInfo info = new DocInfo(milvusId, docId, docName, page, chunkIndex, text,
                    terms.size(), tf);
            docs.put(milvusId, info);
            totalLength += terms.size();
            // 更新 docFreq
            for (String term : tf.keySet()) docFreq.merge(term, 1, Integer::sum);
        }

        void remove(String milvusId) {
            DocInfo info = docs.remove(milvusId);
            if (info == null) return;
            totalLength -= info.length;
            for (String term : info.termFreq.keySet()) {
                docFreq.computeIfPresent(term, (k, v) -> v <= 1 ? null : v - 1);
            }
        }

        double idf(String term) {
            int n = docFreq.getOrDefault(term, 0);
            if (n == 0) return 0;
            return Math.log(1.0 + (double) docs.size() / n);
        }

        double getAvgDocLength() {
            return docs.isEmpty() ? 1 : (double) totalLength / docs.size();
        }

        boolean isEmpty() { return docs.isEmpty(); }
        int size() { return docs.size(); }

        private static List<String> tokenizeStatic(String text) {
            if (text == null || text.isBlank()) return List.of();
            List<String> tokens = new ArrayList<>();
            String[] parts = TOKENIZER.split(text);
            for (String part : parts) {
                String t = part.strip().toLowerCase();
                if (t.isBlank()) continue;
                if (isMostlyAsciiStatic(t)) {
                    tokens.add(t);
                } else {
                    for (int i = 0; i < t.length() - 1; i++) tokens.add(t.substring(i, i + 2));
                    if (t.length() == 1) tokens.add(t);
                }
            }
            return tokens;
        }

        private static boolean isMostlyAsciiStatic(String s) {
            int ascii = 0;
            for (char c : s.toCharArray()) if (c < 128) ascii++;
            return ascii > s.length() / 2;
        }
    }

    private static class DocInfo {
        final String milvusId;
        final String docId;
        final String docName;
        final int page;
        final int chunkIndex;
        final String text;
        final int length;
        final Map<String, Integer> termFreq;

        DocInfo(String milvusId, String docId, String docName, int page,
                int chunkIndex, String text, int length, Map<String, Integer> termFreq) {
            this.milvusId = milvusId;
            this.docId = docId;
            this.docName = docName;
            this.page = page;
            this.chunkIndex = chunkIndex;
            this.text = text;
            this.length = length;
            this.termFreq = termFreq;
        }
    }

    public record Bm25Hit(String milvusId, String chunkId, String docId, String docName,
                         int page, int chunkIndex, String text, float score) {
    }
}
