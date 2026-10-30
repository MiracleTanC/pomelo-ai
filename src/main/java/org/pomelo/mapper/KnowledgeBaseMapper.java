package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.pomelo.domain.kb.KnowledgeBase;

@Mapper
public interface KnowledgeBaseMapper {

    KnowledgeBase findById(String id);

    List<KnowledgeBase> findAll();

    int insert(KnowledgeBase kb);

    int update(KnowledgeBase kb);

    void deleteById(String id);
}
