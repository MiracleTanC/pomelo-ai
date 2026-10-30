package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.pomelo.domain.doc.DocChunk;

@Mapper
public interface DocChunkMapper {

    DocChunk findById(String id);

    List<DocChunk> findByDocIdOrderByChunkIndexAsc(String docId);

    List<DocChunk> findByDocId(String docId);

    List<DocChunk> findByKbId(String kbId);

    List<DocChunk> findByKbIdAndStatus(@Param("kbId") String kbId, @Param("status") DocChunk.ChunkStatus status);

    long deleteByDocId(String docId);

    long countByDocId(String docId);

    long countByDocIdAndStatus(@Param("docId") String docId, @Param("status") DocChunk.ChunkStatus status);

    int insert(DocChunk chunk);

    int batchInsert(@Param("list") List<DocChunk> list);

    int update(DocChunk chunk);

    void deleteById(String id);
}
