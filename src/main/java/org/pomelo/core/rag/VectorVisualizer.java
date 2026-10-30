package org.pomelo.core.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.milvus.MilvusVectorStore;
import org.pomelo.domain.rag.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 向量可视化降维：对知识库的向量做 PCA → 2D 散点。
 * <p>
 * 轻量实现：不引入第三方 t-SNE 库，使用 PCA（主成分分析）做 2D 降维，
 * 对 768 维向量计算协方差矩阵的前两个特征向量投影。
 * 采样最多 500 条避免计算过重。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorVisualizer {

    private final MilvusVectorStore vectorStore;

    /** 最大采样数 */
    private static final int MAX_SAMPLES = 500;

    /**
     * 对知识库向量做 PCA 2D 降维，返回散点数据。
     */
    public VisualizationResult visualize(String kbId) {
        // 采样：用空向量做 query 召回（Milvus 不支持全量扫描，用随机向量近似采样）
        float[] probe = new float[768];
        Random rnd = new Random(42);
        for (int i = 0; i < 768; i++) probe[i] = rnd.nextFloat() * 0.1f;

        List<RetrievedChunk> samples = new ArrayList<>();
        // 多次采样不同区域
        for (int s = 0; s < 5 && samples.size() < MAX_SAMPLES; s++) {
            float[] q = new float[768];
            for (int i = 0; i < 768; i++) q[i] = (rnd.nextFloat() - 0.5f) * 2;
            try {
                List<RetrievedChunk> hits = vectorStore.search(kbId, q, 100, "content", null);
                for (RetrievedChunk h : hits) {
                    if (samples.size() >= MAX_SAMPLES) break;
                    if (samples.stream().noneMatch(x -> x.chunkId().equals(h.chunkId()))) {
                        samples.add(h);
                    }
                }
            } catch (Exception e) {
                log.debug("采样查询失败: {}", e.getMessage());
            }
        }

        if (samples.size() < 3) {
            return new VisualizationResult(List.of(), 0, "采样不足，无法可视化");
        }

        // 提取向量（从 retrieved chunk 重建不了原始向量，这里用 text hash 做伪坐标）
        // 实际实现：从 Milvus query 原始向量
        List<float[]> vectors = vectorStore.queryVectors(kbId, samples.stream()
                .map(RetrievedChunk::chunkId).toList(), MAX_SAMPLES);

        if (vectors.size() < 3) {
            return new VisualizationResult(List.of(), samples.size(), "向量获取不足");
        }

        // PCA 2D 降维
        List<double[]> points2d = pca2d(vectors);

        List<ScatterPoint> points = new ArrayList<>();
        for (int i = 0; i < samples.size() && i < points2d.size(); i++) {
            RetrievedChunk c = samples.get(i);
            points.add(new ScatterPoint(
                    c.chunkId(), c.docId(), c.docName(),
                    c.page(), c.chunkIndex(), c.text().length() > 80 ? c.text().substring(0, 80) : c.text(),
                    points2d.get(i)[0], points2d.get(i)[1]
            ));
        }

        return new VisualizationResult(points, samples.size(), "ok");
    }

    /**
     * PCA 2D 降维：计算前两个主成分并投影。
     */
    private List<double[]> pca2d(List<float[]> vectors) {
        int n = vectors.size();
        int dim = vectors.get(0).length;

        // 1. 中心化
        double[] mean = new double[dim];
        for (float[] v : vectors) {
            for (int j = 0; j < dim; j++) mean[j] += v[j];
        }
        for (int j = 0; j < dim; j++) mean[j] /= n;

        double[][] centered = new double[n][dim];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < dim; j++) centered[i][j] = vectors.get(i)[j] - mean[j];
        }

        // 2. 协方差矩阵 dim×dim（对 768 维太大，改用 SVD 近似）
        // 简化实现：用 power iteration 找前两个主成分
        double[] pc1 = powerIteration(centered, dim, n, 100);
        // 去除 pc1 分量
        for (int i = 0; i < n; i++) {
            double proj = dot(centered[i], pc1);
            for (int j = 0; j < dim; j++) centered[i][j] -= proj * pc1[j];
        }
        double[] pc2 = powerIteration(centered, dim, n, 100);

        // 3. 投影到 2D
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(new double[]{ dot(vectors.get(i), pc1), dot(vectors.get(i), pc2) });
        }
        return result;
    }

    /**
     * Power iteration：找到协方差矩阵的主特征向量。
     */
    private double[] powerIteration(double[][] centered, int dim, int n, int iterations) {
        double[] v = new double[dim];
        Random rnd = new Random(42);
        for (int j = 0; j < dim; j++) v[j] = rnd.nextGaussian();

        for (int iter = 0; iter < iterations; iter++) {
            double[] next = new double[dim];
            // next = centered^T * (centered * v) = centered^T * centered * v
            for (int i = 0; i < n; i++) {
                double dot = dot(centered[i], v);
                for (int j = 0; j < dim; j++) next[j] += centered[i][j] * dot;
            }
            // 归一化
            double norm = Math.sqrt(dot(next, next));
            if (norm < 1e-10) break;
            for (int j = 0; j < dim; j++) v[j] = next[j] / norm;
        }
        return v;
    }

    private double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
        return s;
    }

    private double dot(float[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
        return s;
    }

    // ---- 结果 DTO ----

    public record VisualizationResult(List<ScatterPoint> points, int totalSamples, String message) {
    }

    public record ScatterPoint(
            String chunkId, String docId, String docName,
            int page, int chunkIndex, String preview,
            double x, double y
    ) {
    }
}
