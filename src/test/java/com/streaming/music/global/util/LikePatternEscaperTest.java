package com.streaming.music.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LikePatternEscaperTest {

	@Test
	@DisplayName("백슬래시, 퍼센트, 언더스코어를 이스케이프한다")
	void escapeSpecialCharacters() {
		assertThat(LikePatternEscaper.escapeLikePattern("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
	}

	@Test
	@DisplayName("일반 문자열은 변하지 않는다")
	void keepPlainText() {
		assertThat(LikePatternEscaper.escapeLikePattern("사랑 love")).isEqualTo("사랑 love");
	}

	@Test
	@DisplayName("null 입력은 빈 문자열로 처리한다")
	void escapeNullAsEmptyString() {
		assertThat(LikePatternEscaper.escapeLikePattern(null)).isEmpty();
	}

	@Test
	@DisplayName("포함 검색 패턴은 앞뒤 공백을 제거하고 %로 감싼다")
	void buildContainsPattern() {
		assertThat(LikePatternEscaper.toContainsPattern("  a_b ")).isEqualTo("%a\\_b%");
	}
}
