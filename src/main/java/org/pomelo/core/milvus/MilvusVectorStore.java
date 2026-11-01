package org.pomelo.core.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.chunk.TextChunk;
import org.pomelo.domain.rag.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Milvus 向量库：每个知识库对应一个独立 Collection。
 * <p>
 * Schema: id(自增主键) / vector(embed-dimension维) / kb_id / doc_id / doc_name / page / chunk_index / text
 * 索引: HNSW + COSINE
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStore {

    private final MilvusClientV2 client;
    private final Gson gson = new Gson();

    @Value("${pomelo.milvus.collection-prefix}")
    private String collectionPrefix;

    @Value("${pomelo.llm.embed-dimension}")
    private int dimension;

    public static final String FIELD_ID = "id";
    public static final String FIELD_VECTOR = "vector";
    public static final String FIELD_KB_ID = "kb_id";
    public static final String FIELD_DOC_ID = "doc_id";
    public static final String FIELD_DOC_NAME = "doc_name";
    public static final String FIELD_PAGE = "page";
    public static final String FIELD_CHUNK_INDEX = "chunk_index";
    public static final String FIELD_TEXT = "text";
    /** 向量类型：content=正文切片, summary=摘要, keywords=关键词 */
    public static final String FIELD_VEC_TYPE = "vec_type";
    /** 切片 ID（对应 doc_chunk 表的 id），用于增量删除 */
    public static final String FIELD_CHUNK_ID = "chunk_id";

    private String collectionName(String kbId) {
        // Milvus Collection 名只允许数字、字母、下划线，UUID 中的 '-' 需替换
        return collectionPrefix + kbId.replace("-", "_");
    }

    /**
     * 确保知识库对应的 Collection 存在并已加载。
     * 若已存在但维度不匹配则自动删除重建。
     */
    public void ensureCollection(String kbId) {
        String name = collectionName(kbId);
        if (client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
            // 检查已有 collection 的向量维度是否匹配当前配置
            try {
                DescribeCollectionResp desc = client.describeCollection(
                        DescribeCollectionReq.builder().collectionName(name).build());
                int existingDim = desc.getCollectionSchema().getFieldSchemaList().stream()
                        .filter(f -> f.getName().equals(FIELD_VECTOR))
                        .findFirst()
                        .map(f -> f.getDimension())
                        .orElse(0);
                if (existingDim != 0 && existingDim != dimension) {
                    log.warn("Collection {} 维度不匹配（已有 {}d，当前配置 {}d），自动删除重建",
                            name, existingDim, dimension);
                    client.dropCollection(DropCollectionReq.builder().collectionName(name).build());
                }
            } catch (Exception e) {
                log.warn("检查 Collection 维度失败: {} -> {}", name, e.getMessage());
            }
        }
        if (client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_ID).dataType(DataType.Int64)
                .isPrimaryKey(Boolean.TRUE).autoID(Boolean.TRUE).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_VECTOR).dataType(DataType.FloatVector).dimension(dimension).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_KB_ID).dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DOC_ID).dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DOC_NAME).dataType(DataType.VarChar).maxLength(255).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_PAGE).dataType(DataType.Int32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_INDEX).dataType(DataType.Int32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TEXT).dataType(DataType.VarChar).maxLength(8192).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_VEC_TYPE).dataType(DataType.VarChar).maxLength(32)
                .isNullable(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_ID).dataType(DataType.VarChar).maxLength(64)
                .isNullable(true).build());

        IndexParam index = IndexParam.builder()
                .fieldName(FIELD_VECTOR)
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", 16, "efConstruction", 200))
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(name)
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(index))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
        log.info("已创建并加载 Milvus Collection: {}", name);
    }

    /**
     * 批量插入向量（与 chunks 同序）。
     */
    public void insert(String kbId, String docId, String docName, List<TextChunk> chunks, List<float[]> vectors) {
        String name = collectionName(kbId);
        ensureCollection(kbId);
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            JsonObject row = new JsonObject();
            row.add(FIELD_VECTOR, gson.toJsonTree(vectors.get(i)));
            row.addProperty(FIELD_KB_ID, kbId);
            row.addProperty(FIELD_DOC_ID, docId);
            row.addProperty(FIELD_DOC_NAME, docName);
            row.addProperty(FIELD_PAGE, c.page());
            row.addProperty(FIELD_CHUNK_INDEX, c.index());
            String text = c.text();
            if (text != null && text.length() > 8000) text = text.substring(0, 8000);
            row.addProperty(FIELD_TEXT, text);
            row.addProperty(FIELD_VEC_TYPE, "content");
            // chunk_id 用空值（兼容旧 insert 签名，带 chunkId 的版本用 insertWithChunkIds）
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(name).data(rows).build());
        log.info("插入 {} 条向量到 Collection {}", rows.size(), name);
    }

    /**
     * 批量插入向量（带 chunkId，支持增量删除）。
     */
    public void insertWithChunkIds(String kbId, String docId, String docName,
                                    List<String> chunkIds, List<TextChunk> chunks, List<float[]> vectors) {
        String name = collectionName(kbId);
        ensureCollection(kbId);
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            JsonObject row = new JsonObject();
            row.add(FIELD_VECTOR, gson.toJsonTree(vectors.get(i)));
            row.addProperty(FIELD_KB_ID, kbId);
            row.addProperty(FIELD_DOC_ID, docId);
            row.addProperty(FIELD_DOC_NAME, docName);
            row.addProperty(FIELD_PAGE, c.page());
            row.addProperty(FIELD_CHUNK_INDEX, c.index());
            String text = c.text();
            if (text != null && text.length() > 8000) text = text.substring(0, 8000);
            row.addProperty(FIELD_TEXT, text);
            row.addProperty(FIELD_VEC_TYPE, "content");
            row.addProperty(FIELD_CHUNK_ID, chunkIds.get(i));
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(name).data(rows).build());
        log.info("插入 {} 条向量到 Collection {}", rows.size(), name);
    }

    /**
     * 插入单条元数据向量（摘要/关键词向量化）。
     */
    public void insertMetadataVector(String kbId, String docId, String docName,
                                     String chunkId, String vecType, String text, float[] vector) {
        String name = collectionName(kbId);
        ensureCollection(kbId);
        JsonObject row = new JsonObject();
        row.add(FIELD_VECTOR, gson.toJsonTree(vector));
        row.addProperty(FIELD_KB_ID, kbId);
        row.addProperty(FIELD_DOC_ID, docId);
        row.addProperty(FIELD_DOC_NAME, docName);
        row.addProperty(FIELD_PAGE, 0);
        row.addProperty(FIELD_CHUNK_INDEX, -1);
        String t = text != null && text.length() > 8000 ? text.substring(0, 8000) : text;
        row.addProperty(FIELD_TEXT, t);
        row.addProperty(FIELD_VEC_TYPE, vecType);
        row.addProperty(FIELD_CHUNK_ID, chunkId);
        client.insert(InsertReq.builder().collectionName(name).data(List.of(row)).build());
        log.info("插入元数据向量({})到 Collection {}", vecType, name);
    }

    /**
     * 按 chunkId 删除向量（用于增量向量化删除旧向量）。
     */
    public void deleteByChunkId(String kbId, String chunkId) {
        String name = collectionName(kbId);
        client.delete(DeleteReq.builder()
                .collectionName(name)
                .filter(FIELD_CHUNK_ID + " == \"" + chunkId + "\"")
                .build());
    }

    /**
     * 删除文档的某种类型的向量（如全部 summary 向量）。
     */
    public void deleteByDocAndType(String kbId, String docId, String vecType) {
        String name = collectionName(kbId);
        client.delete(DeleteReq.builder()
                .collectionName(name)
                .filter(FIELD_DOC_ID + " == \"" + docId + "\" and "
                        + FIELD_VEC_TYPE + " == \"" + vecType + "\"")
                .build());
    }

    /**
     * 向量检索 Top-K，按 kb_id 过滤。可选按 vec_type 过滤。
     */
    public List<RetrievedChunk> search(String kbId, float[] queryVector, int topK) {
        return search(kbId, queryVector, topK, null);
    }

    /**
     * 向量检索 Top-K，按 kb_id 过滤。可选按 vec_type 过滤，可选排除停用文档。
     */
    public List<RetrievedChunk> search(String kbId, float[] queryVector, int topK, String vecType) {
        return search(kbId, queryVector, topK, vecType, null);
    }

    /**
     * 向量检索 Top-K，按 kb_id + 可选 vec_type 过滤，可选排除指定 docId 列表。
     */
    public List<RetrievedChunk> search(String kbId, float[] queryVector, int topK,
                                        String vecType, List<String> excludeDocIds) {
        String name = collectionName(kbId);
        ensureCollection(kbId);
        String filter = FIELD_KB_ID + " == \"" + kbId + "\"";
        if (vecType != null && !vecType.isBlank()) {
            filter += " and " + FIELD_VEC_TYPE + " == \"" + vecType + "\"";
        }
        if (excludeDocIds != null && !excludeDocIds.isEmpty()) {
            String idList = excludeDocIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
            filter += " and " + FIELD_DOC_ID + " not in [" + idList + "]";
        }
        SearchResp resp = client.search(SearchReq.builder()
                .collectionName(name)
                .data(Collections.singletonList(new FloatVec(queryVector)))
                .annsField(FIELD_VECTOR)
                .filter(filter)
                .limit(topK)
                .outputFields(Arrays.asList(FIELD_DOC_ID, FIELD_DOC_NAME, FIELD_PAGE,
                        FIELD_CHUNK_INDEX, FIELD_TEXT, FIELD_VEC_TYPE, FIELD_CHUNK_ID))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        List<RetrievedChunk> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = resp.getSearchResults();
        if (!searchResults.isEmpty()) {
            for (SearchResp.SearchResult r : searchResults.get(0)) {
                Map<String, Object> entity = r.getEntity();
                String chunkId = entity.get(FIELD_CHUNK_ID) != null
                        ? String.valueOf(entity.get(FIELD_CHUNK_ID)) : String.valueOf(r.getId());
                results.add(RetrievedChunk.of(
                        chunkId,
                        String.valueOf(entity.get(FIELD_DOC_ID)),
                        String.valueOf(entity.get(FIELD_DOC_NAME)),
                        kbId,
                        toInt(entity.get(FIELD_PAGE)),
                        toInt(entity.get(FIELD_CHUNK_INDEX)),
                        String.valueOf(entity.get(FIELD_TEXT)),
                        r.getScore()
                ));
            }
        }
        return results;
    }

    /**
     * 按文档删除该文档所有向量。
     */
    public void deleteByDoc(String kbId, String docId) {
        String name = collectionName(kbId);
        client.delete(DeleteReq.builder()
                .collectionName(name)
                .filter(FIELD_DOC_ID + " == \"" + docId + "\"")
                .build());
        log.info("已删除文档 {} 在 Collection {} 中的向量", docId, name);
    }

    /**
     * 删除整个知识库 Collection。
     */
    public void dropCollection(String kbId) {
        String name = collectionName(kbId);
        if (client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
            client.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq.builder()
                    .collectionName(name).build());
            log.info("已删除 Collection {}", name);
        }
    }

    /**
     * 查询向量（按 chunk_id 过滤），用于可视化降维。
     */
    public List<float[]> queryVectors(String kbId, List<String> chunkIds, int limit) {
        String name = collectionName(kbId);
        if (!client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
            return List.of();
        }
        try {
            String idFilter = chunkIds.stream()
                    .map(id -> "\"" + id + "\"")
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            io.milvus.v2.service.vector.response.QueryResp resp = client.query(
                    io.milvus.v2.service.vector.request.QueryReq.builder()
                            .collectionName(name)
                            .filter(FIELD_CHUNK_ID + " in [" + idFilter + "]")
                            .outputFields(List.of(FIELD_VECTOR))
                            .limit(limit)
                            .consistencyLevel(ConsistencyLevel.STRONG)
                            .build());
            List<float[]> result = new ArrayList<>();
            for (io.milvus.v2.service.vector.response.QueryResp.QueryResult r : resp.getQueryResults()) {
                Object vec = r.getEntity().get(FIELD_VECTOR);
                if (vec instanceof float[] arr) {
                    result.add(arr);
                } else if (vec != null) {
                    // Gson 解析的 LinkedTreeMap 或 List
                    String json = gson.toJson(vec);
                    float[] floats = gson.fromJson(json, float[].class);
                    result.add(floats);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("queryVectors 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(v));
    }
}
