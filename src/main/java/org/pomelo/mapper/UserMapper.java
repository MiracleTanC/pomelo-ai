package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.pomelo.domain.auth.UserEntity;

@Mapper
public interface UserMapper {

    UserEntity findByUsername(String username);

    boolean existsByUsername(String username);

    List<UserEntity> findAll();

    UserEntity findById(String id);

    int insert(UserEntity user);

    int update(UserEntity user);

    void deleteById(String id);
}
