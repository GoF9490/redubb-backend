package com.streaming.music.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streaming.music.global.config.StorageConfig;
import com.streaming.music.storage.service.MinioStorageService;
import com.streaming.music.global.properties.StorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** presigned URL → 내부 경로 변환 단위 테스트. 실제 MinioClient가 서명한 URL을 사용한다(네트워크 호출 없음). */
class InternalRedirectPathConverterTest {

	@Test
	@DisplayName("실제 SDK가 서명한 URL의 쿼리는 변환 후에도 바이트 단위로 동일하다")
	void preserveQueryOfRealPresignedUrl() {
		StorageProperties properties = new StorageProperties(
				"http://rustfs:9000", "us-east-1", "test-access", "test-secret", "audio-storage", 60);
		MinioClient client = new StorageConfig().minioClient(properties);
		String url = new MinioStorageService(client, properties)
				.createPresignedGetUrl("original/2026/11/u1.mp3", 60);
		String originalQuery = url.substring(url.indexOf('?'));

		String redirect = InternalRedirectPathConverter.toInternalRedirectUri(url, "audio-storage");

		assertThat(redirect).startsWith("/internal-storage/original/2026/11/u1.mp3?");
		assertThat(redirect.substring(redirect.indexOf('?'))).isEqualTo(originalQuery);
		assertThat(originalQuery).contains("%2F");
		assertThat(redirect).doesNotContain("rustfs").doesNotContain("audio-storage");
	}

	@Test
	@DisplayName("포트가 없는 host도 변환된다")
	void convertUrlWithoutPort() {
		String redirect = InternalRedirectPathConverter.toInternalRedirectUri(
				"https://storage.example.com/audio-storage/original/a.wav?X-Amz-Signature=ab%3D%2F", "audio-storage");

		assertThat(redirect).isEqualTo("/internal-storage/original/a.wav?X-Amz-Signature=ab%3D%2F");
	}

	@Test
	@DisplayName("버킷 접두 불일치·쿼리 없음·형식 오류·null은 IllegalStateException이고 메시지에 URL이 없다")
	void rejectUnexpectedUrl() {
		String secretUrl = "http://rustfs:9000/other/original/a.mp3?X-Amz-Signature=SECRET";

		assertThatThrownBy(() -> InternalRedirectPathConverter.toInternalRedirectUri(secretUrl, "audio-storage"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("SECRET").hasMessageNotContaining("rustfs");
		assertThatThrownBy(() -> InternalRedirectPathConverter.toInternalRedirectUri(
				"http://rustfs:9000/audio-storage/original/a.mp3", "audio-storage"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> InternalRedirectPathConverter.toInternalRedirectUri("not-a-url?x=1", "audio-storage"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> InternalRedirectPathConverter.toInternalRedirectUri(
				"http://rustfs:9000/audio-storage/?x=1", "audio-storage"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> InternalRedirectPathConverter.toInternalRedirectUri(null, "audio-storage"))
				.isInstanceOf(IllegalStateException.class);
	}
}
