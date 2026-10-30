package org.pomelo.core.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端配置（MilvusClientV2）。
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${pomelo.milvus.host}")
    private String host;

    @Value("${pomelo.milvus.port}")
    private int port;

    @Bean
    public MilvusClientV2 milvusClientV2() {
        String uri = String.format("http://%s:%d", host, port);
        log.info("连接 Milvus: {}", uri);
        return new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
    }
}
