package org.pomelo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.api.kb.req.KbCreateRequest;
import org.pomelo.api.kb.req.KbUpdateRequest;
import org.pomelo.api.kb.vo.KbVo;
import org.pomelo.common.exception.BizException;
import org.pomelo.core.milvus.MilvusVectorStore;
import org.pomelo.domain.kb.KnowledgeBase;
import org.pomelo.domain.kb.MetaFieldConfig;
import org.pomelo.mapper.KnowledgeBaseMapper;
import org.pomelo.mapper.MetaFieldConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbRepository;
    private final MetaFieldConfigMapper metaFieldConfigRepository;
    private final MilvusVectorStore vectorStore;

    @Transactional
    public KbVo create(KbCreateRequest req) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .name(req.name())
                .description(req.description())
                .chunkSize(req.chunkSize() != null ? req.chunkSize() : 500)
                .chunkOverlap(req.chunkOverlap() != null ? req.chunkOverlap() : 50)
                .topK(req.topK() != null ? req.topK() : 8)
                .rerankTopK(req.rerankTopK() != null ? req.rerankTopK() : 4)
                .similarityThreshold(req.similarityThreshold() != null ? req.similarityThreshold() : 0.3)
                .autoVectorize(req.autoVectorize() != null && req.autoVectorize())
                .chunkStrategy(req.chunkStrategy() != null ? req.chunkStrategy() : "general")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        kb.setId(UUID.randomUUID().toString());
        kbRepository.insert(kb);
        kb.setCollectionName(kb.getId());
        kbRepository.update(kb);

        // 预建 Milvus Collection
        vectorStore.ensureCollection(kb.getId());

        // 初始化默认元数据字段配置
        initDefaultFieldConfigs(kb.getId());

        return toVo(kb);
    }

    public List<KbVo> list() {
        return kbRepository.findAll().stream().map(this::toVo).toList();
    }

    public KbVo get(String id) {
        return toVo(getEntity(id));
    }

    public KnowledgeBase getEntity(String id) {
        KnowledgeBase kb = kbRepository.findById(id);
        if (kb == null) {
            throw new BizException("知识库不存在: " + id);
        }
        return kb;
    }

    @Transactional
    public KbVo update(String id, KbUpdateRequest req) {
        KnowledgeBase kb = getEntity(id);
        if (req.name() != null) kb.setName(req.name());
        if (req.description() != null) kb.setDescription(req.description());
        if (req.chunkSize() != null) kb.setChunkSize(req.chunkSize());
        if (req.chunkOverlap() != null) kb.setChunkOverlap(req.chunkOverlap());
        if (req.topK() != null) kb.setTopK(req.topK());
        if (req.rerankTopK() != null) kb.setRerankTopK(req.rerankTopK());
        if (req.similarityThreshold() != null) kb.setSimilarityThreshold(req.similarityThreshold());
        if (req.autoVectorize() != null) kb.setAutoVectorize(req.autoVectorize());
        if (req.chunkStrategy() != null) kb.setChunkStrategy(req.chunkStrategy());
        kb.setUpdatedAt(Instant.now());
        kbRepository.update(kb);
        return toVo(kb);
    }

    @Transactional
    public void delete(String id) {
        KnowledgeBase kb = getEntity(id);
        vectorStore.dropCollection(kb.getId());
        metaFieldConfigRepository.deleteByKbId(id);
        kbRepository.deleteById(id);
    }

    @Transactional
    public void incrStats(String kbId, int docDelta, int chunkDelta) {
        KnowledgeBase kb = getEntity(kbId);
        kb.setDocCount(kb.getDocCount() + docDelta);
        kb.setChunkCount(kb.getChunkCount() + chunkDelta);
        kb.setUpdatedAt(Instant.now());
        kbRepository.update(kb);
    }

    // ==================== 元数据字段配置 ====================

    public List<MetaFieldConfig> getFieldConfigs(String kbId) {
        getEntity(kbId);
        return metaFieldConfigRepository.findByKbIdOrderBySortOrderAsc(kbId);
    }

    @Transactional
    public MetaFieldConfig addFieldConfig(String kbId, org.pomelo.api.meta.req.MetaFieldConfigRequest req) {
        getEntity(kbId);
        // 检查 fieldKey 不重复
        MetaFieldConfig existing = metaFieldConfigRepository.findByKbIdAndFieldKey(kbId, req.fieldKey());
        if (existing != null) {
            throw new BizException("字段 key 已存在: " + req.fieldKey());
        }
        MetaFieldConfig fc = MetaFieldConfig.builder()
                .kbId(kbId)
                .fieldKey(req.fieldKey())
                .fieldName(req.fieldName())
                .fieldType(req.fieldType() != null ? req.fieldType() : "string")
                .extractType(req.extractType())
                .extractRule(req.extractRule())
                .required(req.required() != null && req.required())
                .searchable(req.searchable() != null && req.searchable())
                .vectorized(req.vectorized() != null && req.vectorized())
                .displayInList(req.displayInList() != null && req.displayInList())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 99)
                .enabled(req.enabled() == null || req.enabled())
                .createdAt(Instant.now())
                .build();
        fc.setId(UUID.randomUUID().toString());
        metaFieldConfigRepository.insert(fc);
        return fc;
    }

    @Transactional
    public MetaFieldConfig updateFieldConfig(String configId,
                                              org.pomelo.api.meta.req.MetaFieldConfigRequest req) {
        MetaFieldConfig fc = metaFieldConfigRepository.findById(configId);
        if (fc == null) {
            throw new BizException("字段配置不存在: " + configId);
        }
        fc.setFieldName(req.fieldName());
        fc.setFieldType(req.fieldType() != null ? req.fieldType() : fc.getFieldType());
        fc.setExtractType(req.extractType());
        fc.setExtractRule(req.extractRule());
        fc.setRequired(req.required() != null && req.required());
        fc.setSearchable(req.searchable() != null && req.searchable());
        fc.setVectorized(req.vectorized() != null && req.vectorized());
        fc.setDisplayInList(req.displayInList() != null && req.displayInList());
        if (req.sortOrder() != null) fc.setSortOrder(req.sortOrder());
        if (req.enabled() != null) fc.setEnabled(req.enabled());
        metaFieldConfigRepository.update(fc);
        return fc;
    }

    @Transactional
    public void deleteFieldConfig(String configId) {
        metaFieldConfigRepository.deleteById(configId);
    }

    // ==================== 默认 13 字段配置 ====================

    private void initDefaultFieldConfigs(String kbId) {
        List<MetaFieldConfig> defaults = new ArrayList<>();
        int order = 0;

        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("file_name").fieldName("文件名称")
                .fieldType("string").extractType("SYSTEM").required(true).searchable(true).displayInList(true).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("title").fieldName("标题")
                .fieldType("string").extractType("DOC_PROPERTY").extractRule("title").searchable(true).displayInList(true).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("author").fieldName("作者")
                .fieldType("string").extractType("DOC_PROPERTY").extractRule("author").searchable(true).displayInList(true).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("org").fieldName("机构")
                .fieldType("string").extractType("LLM").extractRule("从文档内容提取署名机构或发布单位").searchable(true).displayInList(false).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("publish_date").fieldName("发布日期")
                .fieldType("date").extractType("DOC_PROPERTY").extractRule("creationDate").searchable(true).displayInList(true).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("publish_time").fieldName("发布时间")
                .fieldType("datetime").extractType("DOC_PROPERTY").extractRule("creationDate").displayInList(false).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("create_time").fieldName("创建时间")
                .fieldType("datetime").extractType("SYSTEM").displayInList(true).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("keywords").fieldName("关键词")
                .fieldType("list").extractType("LLM").extractRule("从文档内容提取5-10个核心关键词，逗号分隔").searchable(true).vectorized(true).displayInList(false).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("summary").fieldName("摘要")
                .fieldType("text").extractType("LLM").extractRule("用200字以内概括文档核心内容").searchable(true).vectorized(true).displayInList(true).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("topic").fieldName("主题")
                .fieldType("enum").extractType("LLM").extractRule("判断文档所属主题分类：技术规范/管理制度/产品说明/研究报告/其他").searchable(true).displayInList(true).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("terms").fieldName("术语")
                .fieldType("list").extractType("LLM").extractRule("提取文档中的专业术语，每个术语一行").displayInList(false).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("metrics").fieldName("指标信息")
                .fieldType("json").extractType("LLM").extractRule("提取文档中的关键指标(名称/数值/单位)，JSON数组格式").displayInList(false).sortOrder(order++).build());
        defaults.add(MetaFieldConfig.builder().kbId(kbId).fieldKey("vector_status").fieldName("向量化状态")
                .fieldType("enum").extractType("SYSTEM").displayInList(true).sortOrder(order++).build());

        for (MetaFieldConfig fc : defaults) {
            fc.setId(UUID.randomUUID().toString());
        }
        metaFieldConfigRepository.batchInsert(defaults);
        log.info("知识库 {} 初始化 {} 个默认元数据字段", kbId, defaults.size());
    }

    // ==================== VO 转换 ====================

    private KbVo toVo(KnowledgeBase kb) {
        return new KbVo(kb.getId(), kb.getName(), kb.getDescription(),
                kb.getCollectionName(), kb.getDocCount(), kb.getChunkCount(),
                kb.getChunkSize(), kb.getChunkOverlap(), kb.getTopK(),
                kb.getRerankTopK(), kb.getSimilarityThreshold(), kb.isAutoVectorize(),
                kb.getChunkStrategy(), kb.getCreatedAt(), kb.getUpdatedAt());
    }
}
