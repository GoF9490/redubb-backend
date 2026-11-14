package com.streaming.music.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ConfigurationPropertiesScan으로 5종이 실제 빈 등록되고 application.yaml 값이 반영되는지 검증한다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ConfigurationPropertiesScanTest {

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private StorageProperties storageProperties;

	@Autowired
	private AudioProperties audioProperties;

	@Autowired
	private MediaProperties mediaProperties;

	@Autowired
	private CorsProperties corsProperties;

	@Test
	@DisplayName("5종 Properties가 빈으로 등록되고 yaml 기본값이 반영된다")
	void registersPropertiesViaScanAndReflectsYamlValues() {
		assertThat(storageProperties.presignedExpirySeconds()).isEqualTo(60);
		assertThat(audioProperties.ffmpegTimeoutMinutes()).isEqualTo(3);
		assertThat(jwtProperties.secretKey()).startsWith("test-only-");
		assertThat(jwtProperties.allowedRedirectUris())
				.containsExactly("http://localhost/auth/callback", "musicapp://oauth2/callback");
		assertThat(mediaProperties.publicBaseUrl()).isEmpty();
		assertThat(corsProperties.allowedOrigins()).isEmpty();
	}
}
