package com.streaming.music.service;

import com.streaming.music.transcode.service.TranscodeIntegrationTestBase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streaming.music.playback.entity.PlaybackLog;
import com.streaming.music.entity.Track;
import com.streaming.music.user.entity.User;
import com.streaming.music.global.security.JwtTokenProvider;
import com.streaming.music.support.SampleFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 인증 사용자 연결 HTTP 통합 테스트 (T6-7).
 *
 * <p>실제 보안 필터 체인과 실제 JwtTokenProvider로 발급한 토큰을 사용해
 * 업로드 uploader와 재생 이력의 user 연결을 HTTP 경로로 확인한다.
 */
@AutoConfigureMockMvc
class TrackAuthIntegrationTest extends TranscodeIntegrationTestBase {

	/** 저장된 회원 ID와 겹치지 않는 존재하지 않는 회원 ID. */
	private static final long MISSING_USER_ID = Long.MAX_VALUE - 7;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@BeforeEach
	void stubFfmpeg() {
		// 업로드 후 비동기 변환이 시작되므로 FFmpeg Mock이 성공을 반환하게 한다
		stubFfmpegSuccess(() -> {
		});
	}

	private Long saveUser() {
		User user = userRepository.save(User.builder()
				.provider("GOOGLE").providerId("auth-" + UUID.randomUUID())
				.email(UUID.randomUUID() + "@test.com").build());
		createdUserIds.add(user.getUserId());
		return user.getUserId();
	}

	private String bearerOf(Long userId) {
		return "Bearer " + jwtTokenProvider.createToken(userId, "ROLE_USER");
	}

	/** 서명 구간의 첫 글자를 바꿔 서명을 위조한다. */
	private String forgeBearerOf(Long userId) {
		String token = jwtTokenProvider.createToken(userId, "ROLE_USER");
		int signatureStart = token.lastIndexOf('.') + 1;
		char first = token.charAt(signatureStart);
		char replaced = first == 'A' ? 'B' : 'A';
		return "Bearer " + token.substring(0, signatureStart) + replaced + token.substring(signatureStart + 1);
	}

	/** 업로드 요청을 보내고(202 단정) 생성된 trackId를 정리 대상으로 등록해 반환한다. */
	private UUID uploadWith(String authorization) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "sample.mp3", "audio/mpeg",
				SampleFixtures.readSampleBytes(SampleFixtures.TAGGED_MP3));
		var request = multipart("/api/tracks/upload").file(file);
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		String body = mockMvc.perform(request).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		UUID trackId = UUID.fromString(JsonPath.read(body, "$.trackId"));
		createdTrackIds.add(trackId);
		return trackId;
	}

	/** LAZY uploader 접근을 트랜잭션 안에서 수행해 uploader의 userId(없으면 null)를 반환한다. */
	private Long readUploaderId(UUID trackId) {
		return transactionTemplate.execute(status -> {
			Track track = trackRepository.findById(trackId).orElseThrow();
			return track.getUploader() == null ? null : track.getUploader().getUserId();
		});
	}

	@Test
	@DisplayName("유효 토큰 + 저장된 회원으로 업로드하면 202이고 uploader가 토큰의 회원이다")
	void linkUploaderWithValidToken() throws Exception {
		Long userId = saveUser();

		UUID trackId = uploadWith(bearerOf(userId));

		assertThat(readUploaderId(trackId)).isEqualTo(userId);
	}

	@Test
	@DisplayName("토큰 없이 업로드하면 202이고 uploader는 null이다")
	void uploadAnonymouslyWithoutToken() throws Exception {
		UUID trackId = uploadWith(null);

		assertThat(readUploaderId(trackId)).isNull();
	}

	@Test
	@DisplayName("유효 토큰이지만 존재하지 않는 userId여도 업로드는 202이고 uploader는 null이다")
	void uploadAnonymouslyWhenTokenUserMissing() throws Exception {
		UUID trackId = uploadWith(bearerOf(MISSING_USER_ID));

		assertThat(readUploaderId(trackId)).isNull();
	}

	@Test
	@DisplayName("위조된 토큰으로 업로드해도 차단되지 않고 uploader는 null이다")
	void uploadAnonymouslyWithForgedToken() throws Exception {
		Long userId = saveUser();

		UUID trackId = uploadWith(forgeBearerOf(userId));

		assertThat(readUploaderId(trackId)).isNull();
	}

	@Test
	@DisplayName("유효 토큰으로 재생하면 STREAM 이력에 토큰의 회원이 연결된다")
	void linkUserOnPlayWithValidToken() throws Exception {
		Long userId = saveUser();
		UUID trackId = saveReadyTrack();

		mockMvc.perform(get("/api/tracks/{trackId}/play", trackId).header("Authorization", bearerOf(userId)))
				.andExpect(status().isOk());

		List<PlaybackLog> logs = playbackLogRepository.findAll();
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0).getUser().getUserId()).isEqualTo(userId);
	}

	@Test
	@DisplayName("위조된 토큰으로 재생해도 차단되지 않고 이력의 user는 null이다")
	void recordAnonymousLogOnPlayWithForgedToken() throws Exception {
		Long userId = saveUser();
		UUID trackId = saveReadyTrack();

		mockMvc.perform(get("/api/tracks/{trackId}/play", trackId).header("Authorization", forgeBearerOf(userId)))
				.andExpect(status().isOk());

		List<PlaybackLog> logs = playbackLogRepository.findAll();
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0).getUser()).isNull();
	}
}
