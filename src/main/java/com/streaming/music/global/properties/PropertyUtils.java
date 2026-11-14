package com.streaming.music.global.properties;

import java.util.List;

/**
 * 설정 바인딩 값 정제 유틸 (패키지 내부 전용).
 */
final class PropertyUtils {

	private PropertyUtils() {
	}

	/** null이면 빈 리스트, 각 항목은 trim 후 빈 항목 제거. 불변 리스트로 반환한다. */
	static List<String> normalizeList(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream()
				.filter(value -> value != null)
				.map(String::strip)
				.filter(value -> !value.isEmpty())
				.toList();
	}

	/** null이면 빈 문자열, 아니면 앞뒤 공백 제거. */
	static String normalizeText(String value) {
		return value == null ? "" : value.strip();
	}
}
