package com.streaming.music.config;

import com.streaming.music.config.properties.StorageProperties;
import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO SDK 클라이언트 빈 설정. 빌드 시 네트워크 호출은 없다.
 * region을 고정해 presign 시 GetBucketLocation 추가 호출을 생략한다 (PRD-05 §7.1.2).
 */
@Configuration
public class StorageConfig {

    @Bean
    public MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .region(properties.region())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
