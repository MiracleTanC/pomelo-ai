package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.pomelo.domain.kb.MetaFieldConfig;

@Mapper
public interface MetaFieldConfigMapper {

    MetaFieldConfig findById(String id);

    List<MetaFieldConfig> findByKbIdOrderBySortOrderAsc(String kbId);

    List<MetaFieldConfig> findByKbIdAndEnabledTrueOrderBySortOrderAsc(String kbId);

    MetaFieldConfig findByKbIdAndFieldKey(@Param("kbId") String kbId, @Param("fieldKey") String fieldKey);

    void deleteByKbId(String kbId);

    int insert(MetaFieldConfig config);

    int batchInsert(@Param("list") List<MetaFieldConfig> list);

    int update(MetaFieldConfig config);

    void deleteById(String id);
}
