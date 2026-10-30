package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.pomelo.domain.doc.DocFolder;

@Mapper
public interface DocFolderMapper {

    List<DocFolder> findByKbIdOrderBySortOrderAsc(String kbId);

    List<DocFolder> findByKbIdAndParentIdOrderBySortOrderAsc(@Param("kbId") String kbId, @Param("parentId") String parentId);

    void deleteByKbId(String kbId);

    int insert(DocFolder folder);

    void deleteById(String id);
}
