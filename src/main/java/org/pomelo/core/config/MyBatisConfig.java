package org.pomelo.core.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 配置：扫描 mapper 接口。
 * InstantTypeHandler 通过 @MappedTypes 自动注册。
 */
@Configuration
@MapperScan("org.pomelo.mapper")
public class MyBatisConfig {
}
