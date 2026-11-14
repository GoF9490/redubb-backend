package com.streaming.music.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.global.properties.StorageProperties;
import com.streaming.music.storage.service.MinioStorageService;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 MinioClient로 presigned URL을 서명해 region 고정·만료·호스트 반영을 검증한다.
 * region이 고정되어 있으면 네트워크(GetBucketLocation) 호출 없이 서명되므로,
 * 해석 불가능한 호스트(rustfs)에서도 이 테스트가 통과해야 한다.
 */
class StorageConfigTest {

	@Test
	@DisplayName("고정 region으로 네트워크 호출 없이 presigned URL을 서명한다")
	void signsPresignedUrlWithoutNetworkUsingFixedRegion() {
		StorageProperties properties = new StorageProperties(
				"http://rustfs:9000", "us-east-1", "test-access", "test-secret", "audio-storage", 60);
		MinioClient client = new StorageConfig().minioClient(properties);
		MinioStorageService service = new MinioStorageService(client, properties);

		String url = service.createPresignedGetUrl("original/2026/11/u1.mp3", 60);

		assertThat(url).startsWith("http://rustfs:9000/audio-storage/original/2026/11/u1.mp3?");
		assertThat(url).contains("X-Amz-Expires=60");
		assertThat(url).contains("%2Fus-east-1%2Fs3%2Faws4_request");
		assertThat(url).contains("X-Amz-Signature=");
	}
}
