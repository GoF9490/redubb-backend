package com.streaming.music.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RustFS(S3 호환) 스토리지 설정 (app.storage.*, PRD-05 §4.2).
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        @DefaultValue("us-east-1") String region,
        String accessKey,
        String secretKey,
        @DefaultValue("audio-storage") String bucketName,
        @DefaultValue("60") int presignedExpirySeconds
) {

    /** 접근 키/시크릿 키 로그 노출 방지를 위해 마스킹한다. */
    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", region=" + region
                + ", accessKey=****, secretKey=****, bucketName=" + bucketName
                + ", presignedExpirySeconds=" + presignedExpirySeconds + "]";
    }
}
