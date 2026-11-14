package com.streaming.music.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Content-Disposition(RFC 5987/6266) 생성 단위 테스트. */
class ContentDispositionBuilderTest {

	private static final UUID TRACK_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@Test
	@DisplayName("한글 파일명은 filename*에 정확한 퍼센트 인코딩으로, ASCII filename에는 기본값으로 들어간다")
	void encodeKoreanFilename() {
		String header = ContentDispositionBuilder.build("한글 파일.mp3", TRACK_ID, "mp3");

		assertThat(header).isEqualTo("attachment; filename=\"track-" + TRACK_ID + ".mp3\"; "
				+ "filename*=UTF-8''%ED%95%9C%EA%B8%80%20%ED%8C%8C%EC%9D%BC.mp3");
	}

	@Test
	@DisplayName("전부 비ASCII인 이름은 의미 없는 밑줄 이름 대신 track-uuid.ext 기본값이 된다")
	void useDefaultNameForAllNonAscii() {
		String header = ContentDispositionBuilder.build("한글.mp3", TRACK_ID, "mp3");

		assertThat(header).startsWith("attachment; filename=\"track-" + TRACK_ID + ".mp3\";");
		assertThat(header).endsWith("filename*=UTF-8''%ED%95%9C%EA%B8%80.mp3");
	}

	@Test
	@DisplayName("영문이 섞인 이름은 비ASCII만 밑줄로 치환해 유지한다")
	void keepMixedAsciiName() {
		String header = ContentDispositionBuilder.build("곡 Title.mp3", TRACK_ID, "mp3");

		assertThat(header).startsWith("attachment; filename=\"_ Title.mp3\";");
		assertThat(header).endsWith("filename*=UTF-8''%EA%B3%A1%20Title.mp3");
	}

	@Test
	@DisplayName("따옴표·세미콜론·퍼센트·CR/LF가 섞인 이름은 ASCII filename에서 치환되고 헤더에 CR/LF가 없다")
	void neutralizeInjectionCharacters() {
		String header = ContentDispositionBuilder.build("a\"b;c%d\r\nX-Evil: 1.mp3", TRACK_ID, "mp3");

		assertThat(header).isEqualTo("attachment; filename=\"a_b_c_dX-Evil: 1.mp3\"; "
				+ "filename*=UTF-8''a%22b%3Bc%25dX-Evil%3A%201.mp3");
		assertThat(header).doesNotContain("\r").doesNotContain("\n");
		String asciiPart = header.substring(header.indexOf("filename=\"") + 10, header.indexOf("\";"));
		assertThat(asciiPart).doesNotContain("\"", "\\", ";", "%");
	}

	@Test
	@DisplayName("경로 구분자와 CR/LF가 섞인 이름은 경로가 제거되고 마지막 이름만 남는다")
	void stripPathAndControlCharacters() {
		String header = ContentDispositionBuilder.build("a\"b;c%d\r\nX-Evil: 1/..\\x.mp3", TRACK_ID, "mp3");

		assertThat(header).isEqualTo("attachment; filename=\"x.mp3\"; filename*=UTF-8''x.mp3");
		assertThat(header).doesNotContain("\r").doesNotContain("\n");
	}

	@Test
	@DisplayName("filename*에는 attr-char 이외 문자가 인코딩된 형태로만 남는다")
	void leaveOnlyEncodedFormInExtendedFilename() {
		String header = ContentDispositionBuilder.build("a'b(c)d,e=f.mp3", TRACK_ID, "mp3");

		String extended = header.substring(header.indexOf("filename*=UTF-8''") + 17);
		assertThat(extended).isEqualTo("a%27b%28c%29d%2Ce%3Df.mp3");
	}

	@Test
	@DisplayName("null이나 빈 이름, 제어문자뿐인 이름은 기본 이름을 쓴다")
	void useDefaultNameWhenBlank() {
		String expected = "attachment; filename=\"track-" + TRACK_ID + ".mp3\"; "
				+ "filename*=UTF-8''track-" + TRACK_ID + ".mp3";

		assertThat(ContentDispositionBuilder.build(null, TRACK_ID, "mp3")).isEqualTo(expected);
		assertThat(ContentDispositionBuilder.build("   ", TRACK_ID, "mp3")).isEqualTo(expected);
		assertThat(ContentDispositionBuilder.build("\r\n\t", TRACK_ID, "mp3")).isEqualTo(expected);
	}

	@Test
	@DisplayName("255자를 넘는 이름은 절단된다")
	void truncateLongFilename() {
		String header = ContentDispositionBuilder.build("a".repeat(300) + ".mp3", TRACK_ID, "mp3");

		assertThat(header).startsWith("attachment; filename=\"" + "a".repeat(255) + "\";");
		assertThat(header).endsWith("filename*=UTF-8''" + "a".repeat(255));
	}
}
