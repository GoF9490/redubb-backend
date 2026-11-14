package com.streaming.music.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogTailBufferTest {

	private void appendText(LogTailBuffer buffer, String text) {
		char[] chars = text.toCharArray();
		buffer.append(chars, 0, chars.length);
	}

	@Test
	@DisplayName("한도보다 짧은 입력은 그대로 보관한다")
	void keepShortInput() {
		LogTailBuffer buffer = new LogTailBuffer(500);
		appendText(buffer, "hello");
		assertThat(buffer.toString()).isEqualTo("hello");
	}

	@Test
	@DisplayName("긴 입력은 마지막 500자만 보관한다")
	void keepOnlyLastCharacters() {
		LogTailBuffer buffer = new LogTailBuffer(500);
		appendText(buffer, "a".repeat(2000));
		appendText(buffer, "END");
		assertThat(buffer.toString()).hasSize(500).endsWith("END");
	}

	@Test
	@DisplayName("정확히 한도 길이의 입력은 전부 보관한다")
	void keepInputOfExactMaxLength() {
		LogTailBuffer buffer = new LogTailBuffer(10);

		appendText(buffer, "0123456789");

		assertThat(buffer.toString()).isEqualTo("0123456789");
	}

	@Test
	@DisplayName("보관된 로그의 앞뒤 공백은 제거한다")
	void stripSurroundingWhitespace() {
		LogTailBuffer buffer = new LogTailBuffer(500);

		appendText(buffer, "  error line \n");

		assertThat(buffer.toString()).isEqualTo("error line");
	}

	@Test
	@DisplayName("여러 번 나눠 넣어도 500자 이하를 유지한다")
	void keepLimitAcrossAppends() {
		LogTailBuffer buffer = new LogTailBuffer(500);
		for (int i = 0; i < 100; i++) {
			appendText(buffer, "한글로그줄-" + i + "\n");
		}
		assertThat(buffer.toString().length()).isLessThanOrEqualTo(500);
		assertThat(buffer.toString()).endsWith("한글로그줄-99");
	}
}
