package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.pomelo.common.enums.DocStatus;
import org.pomelo.domain.doc.Document;

@Mapper
public interface DocumentMapper {

    Document findById(String id);

    List<Document> findByKbIdOrderByCreatedAtDesc(String kbId);

    List<Document> findByKbIdAndStatusIn(@Param("kbId") String kbId, @Param("statuses") List<DocStatus> statuses);

    List<Document> findByKbIdAndStatusNot(@Param("kbId") String kbId, @Param("status") DocStatus status);

    Document findByKbIdAndSha256(@Param("kbId") String kbId, @Param("sha256") String sha256);

    long countByKbId(String kbId);

    long countByKbIdAndStatus(@Param("kbId") String kbId, @Param("status") DocStatus status);

    int insert(Document doc);

    int update(Document doc);

    void deleteById(String id);
}
