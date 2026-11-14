package com.streaming.music.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * @ConfigurationProperties 5종 바인딩 검증 (PRD-05 §4.2). 스프링 컨텍스트 전체는 띄우지 않는다.
 */
class ConfigurationPropertiesBindingTest {

	@Configuration
	@EnableConfigurationProperties({
			JwtProperties.class, StorageProperties.class, AudioProperties.class,
			MediaProperties.class, CorsProperties.class
	})
	static class PropertiesTestConfig {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesTestConfig.class);

	@Test
	@DisplayName("쉼표 구분 allowed-redirect-uris가 List로 바인딩된다")
	void bindsCommaSeparatedRedirectUris() {
		runner.withPropertyValues(
				"app.jwt.allowed-redirect-uris=http://localhost/auth/callback,musicapp://oauth2/callback")
				.run(context -> assertThat(context.getBean(JwtProperties.class).allowedRedirectUris())
						.containsExactly("http://localhost/auth/callback", "musicapp://oauth2/callback"));
	}

	@Test
	@DisplayName("공백과 후행 쉼표가 있어도 항목이 정제된다")
	void trimsWhitespaceAndTrailingCommas() {
		runner.withPropertyValues("app.jwt.allowed-redirect-uris=a, b ,,")
				.run(context -> assertThat(context.getBean(JwtProperties.class).allowedRedirectUris())
						.containsExactly("a", "b"));
	}

	@Test
	@DisplayName("public-base-url이 빈 값이거나 없으면 빈 문자열이다")
	void returnsEmptyStringWhenPublicBaseUrlIsBlank() {
		runner.withPropertyValues("app.media.public-base-url=")
				.run(context -> assertThat(context.getBean(MediaProperties.class).publicBaseUrl()).isEqualTo(""));
		runner.run(context -> assertThat(context.getBean(MediaProperties.class).publicBaseUrl()).isEqualTo(""));
	}

	@Test
	@DisplayName("allowed-origins가 빈 값이거나 없으면 빈 리스트이다")
	void returnsEmptyListWhenAllowedOriginsIsBlank() {
		runner.withPropertyValues("app.cors.allowed-origins=")
				.run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty());
		runner.run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty());
	}

	@Test
	@DisplayName("단일 오리진은 크기 1 리스트로 바인딩된다")
	void bindsSingleOrigin() {
		runner.withPropertyValues("app.cors.allowed-origins=https://web.example.com")
				.run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
						.containsExactly("https://web.example.com"));
	}

	@Test
	@DisplayName("속성 미지정 시 PRD 기본값이 적용된다")
	void appliesPrdDefaultsWhenUnset() {
		runner.run(context -> {
			StorageProperties storage = context.getBean(StorageProperties.class);
			AudioProperties audio = context.getBean(AudioProperties.class);
			assertThat(storage.presignedExpirySeconds()).isEqualTo(60);
			assertThat(storage.region()).isEqualTo("us-east-1");
			assertThat(storage.bucketName()).isEqualTo("audio-storage");
			assertThat(audio.ffmpegTimeoutMinutes()).isEqualTo(3);
			assertThat(audio.hlsSegmentSeconds()).isEqualTo(6);
			assertThat(audio.hlsAudioBitrate()).isEqualTo("192k");
			assertThat(audio.maxArtworkSizeBytes()).isEqualTo(10485760L);
			assertThat(context.getBean(JwtProperties.class).accessTokenExpirationMs()).isEqualTo(86400000L);
		});
	}

	@Test
	@DisplayName("주입한 값이 기본값을 덮어쓴다")
	void overridesDefaultsWithInjectedValues() {
		runner.withPropertyValues(
				"app.storage.presigned-expiry-seconds=120",
				"app.audio.ffmpeg-timeout-minutes=5")
				.run(context -> {
					assertThat(context.getBean(StorageProperties.class).presignedExpirySeconds()).isEqualTo(120);
					assertThat(context.getBean(AudioProperties.class).ffmpegTimeoutMinutes()).isEqualTo(5);
				});
	}

	@Test
	@DisplayName("toString은 시크릿 값을 노출하지 않는다")
	void masksSecretsInToString() {
		runner.withPropertyValues(
				"app.jwt.secret-key=jwt-secret-value",
				"app.storage.access-key=access-value",
				"app.storage.secret-key=storage-secret-value")
				.run(context -> {
					String jwt = context.getBean(JwtProperties.class).toString();
					String storage = context.getBean(StorageProperties.class).toString();
					assertThat(jwt).doesNotContain("jwt-secret-value");
					assertThat(storage).doesNotContain("access-value").doesNotContain("storage-secret-value");
				});
	}
}
