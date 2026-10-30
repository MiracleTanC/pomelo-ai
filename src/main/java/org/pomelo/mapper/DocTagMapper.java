package org.pomelo.mapper;

import java.util.List;
import java.util.Set;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.pomelo.domain.doc.DocTag;

@Mapper
public interface DocTagMapper {

    List<DocTag> findByDocId(String docId);

    List<DocTag> findByKbId(String kbId);

    List<DocTag> findByKbIdAndTagIn(@Param("kbId") String kbId, @Param("tags") Set<String> tags);

    void deleteByDocId(String docId);

    void deleteByKbId(String kbId);

    int insert(DocTag tag);

    void deleteById(String id);
}
