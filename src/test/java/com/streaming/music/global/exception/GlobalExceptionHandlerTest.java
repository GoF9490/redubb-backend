package com.streaming.music.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = ErrorTestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles({"local", "test"})
class GlobalExceptionHandlerTest {

	private static final String TIMESTAMP_PATTERN =
			"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+09:00$";

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("잘못된 오디오 형식이면 400 INVALID_AUDIO_FORMAT")
	void returnInvalidAudioFormat() throws Exception {
		mockMvc.perform(get("/test/audio-format"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_AUDIO_FORMAT"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("잘못된 앨범자켓이면 400 INVALID_ARTWORK")
	void returnInvalidArtwork() throws Exception {
		mockMvc.perform(get("/test/artwork"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ARTWORK"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("잘못된 UUID 경로 변수면 400 INVALID_REQUEST")
	void returnInvalidRequestOnBadUuid() throws Exception {
		mockMvc.perform(get("/test/tracks/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("필수 파라미터나 파트가 누락되면 400 INVALID_REQUEST")
	void returnInvalidRequestOnMissingParts() throws Exception {
		mockMvc.perform(get("/test/required-param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mockMvc.perform(post("/test/upload").contentType(MediaType.MULTIPART_FORM_DATA))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("@Valid 검증에 실패하면 400 INVALID_REQUEST")
	void returnInvalidRequestOnValidationFailure() throws Exception {
		mockMvc.perform(post("/test/valid")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("트랙이 없으면 404 TRACK_NOT_FOUND")
	void returnTrackNotFound() throws Exception {
		mockMvc.perform(get("/test/not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("트랙이 준비되지 않았으면 409 TRACK_NOT_READY")
	void returnTrackNotReady() throws Exception {
		mockMvc.perform(get("/test/not-ready"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TRACK_NOT_READY"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("멀티파트 용량을 초과하면 413 PAYLOAD_TOO_LARGE")
	void returnPayloadTooLarge() throws Exception {
		mockMvc.perform(get("/test/too-large"))
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("예상치 못한 예외면 500 INTERNAL_ERROR이고 상세와 스택을 노출하지 않는다")
	void hideDetailOnInternalError() throws Exception {
		MvcResult result = mockMvc.perform(get("/test/unexpected"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.timestamp").exists())
				.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body)
				.doesNotContain(ErrorTestController.SECRET_MARKER)
				.doesNotContain("IllegalStateException")
				.doesNotContain("stackTrace")
				.doesNotContain("trace")
				.doesNotContain("at com.streaming");
	}

	@Test
	@DisplayName("에러 응답 필드는 code, message, timestamp 3개뿐이다")
	void keepOnlyThreeFields() throws Exception {
		mockMvc.perform(get("/test/not-found"))
				.andExpect(jsonPath("$.*", hasSize(3)))
				.andExpect(jsonPath("$.code").exists())
				.andExpect(jsonPath("$.message").exists())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("timestamp는 ISO-8601 +09:00 형식이다")
	void formatTimestampAsIsoWithSeoulOffset() throws Exception {
		MvcResult result = mockMvc.perform(get("/test/not-found"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_PATTERN)))
				.andReturn();

		String body = result.getResponse().getContentAsString();
		String timestamp = body.replaceAll(".*\"timestamp\":\"([^\"]+)\".*", "$1");
		assertThat(OffsetDateTime.parse(timestamp).getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
	}

	@Test
	@DisplayName("존재하지 않는 경로는 500이 아니라 404를 반환한다")
	void returnNotFoundOnUnknownPath() throws Exception {
		mockMvc.perform(get("/test/does-not-exist"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.timestamp").exists());
	}
}
