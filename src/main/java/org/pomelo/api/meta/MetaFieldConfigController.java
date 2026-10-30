package org.pomelo.api.meta;

import lombok.RequiredArgsConstructor;
import org.pomelo.api.meta.req.MetaFieldConfigRequest;
import org.pomelo.api.meta.vo.MetaFieldConfigVo;
import org.pomelo.common.api.Result;
import org.pomelo.domain.kb.MetaFieldConfig;
import org.pomelo.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库元数据字段配置管理：动态增删改查字段配置。
 */
@RestController
@RequestMapping("/api/kb/{kbId}/meta-field")
@RequiredArgsConstructor
public class MetaFieldConfigController {

    private final KnowledgeBaseService kbService;

    /** 查询知识库的全部字段配置 */
    @GetMapping
    public Result<List<MetaFieldConfigVo>> list(@PathVariable String kbId) {
        return Result.ok(kbService.getFieldConfigs(kbId).stream()
                .map(this::toVo).toList());
    }

    /** 新增字段配置 */
    @PostMapping
    public Result<MetaFieldConfigVo> add(@PathVariable String kbId,
                                         @RequestBody MetaFieldConfigRequest req) {
        MetaFieldConfig fc = kbService.addFieldConfig(kbId, req);
        return Result.ok(toVo(fc));
    }

    /** 修改字段配置 */
    @PutMapping("/{configId}")
    public Result<MetaFieldConfigVo> update(@PathVariable String kbId,
                                            @PathVariable String configId,
                                            @RequestBody MetaFieldConfigRequest req) {
        MetaFieldConfig fc = kbService.updateFieldConfig(configId, req);
        return Result.ok(toVo(fc));
    }

    /** 删除字段配置 */
    @DeleteMapping("/{configId}")
    public Result<Void> delete(@PathVariable String kbId,
                               @PathVariable String configId) {
        kbService.deleteFieldConfig(configId);
        return Result.ok();
    }

    private MetaFieldConfigVo toVo(MetaFieldConfig fc) {
        return new MetaFieldConfigVo(
                fc.getId(), fc.getKbId(), fc.getFieldKey(), fc.getFieldName(),
                fc.getFieldType(), fc.getExtractType(), fc.getExtractRule(),
                fc.isRequired(), fc.isSearchable(), fc.isVectorized(),
                fc.isDisplayInList(), fc.getSortOrder(), fc.isEnabled(),
                fc.getCreatedAt()
        );
    }
}
