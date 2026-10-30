package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.pomelo.domain.doc.DocMetadata;

@Mapper
public interface DocMetadataMapper {

    List<DocMetadata> findByDocId(String docId);

    List<DocMetadata> findByDocIdIn(@Param("docIds") List<String> docIds);

    void deleteByDocId(String docId);

    int insert(DocMetadata metadata);

    int batchInsert(@Param("list") List<DocMetadata> list);

    void deleteById(String id);
}
