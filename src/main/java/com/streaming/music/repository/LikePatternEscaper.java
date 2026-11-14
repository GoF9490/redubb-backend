package com.streaming.music.repository;

/**
 * JPQL LIKE 검색용 패턴 유틸.
 * 사용자 입력의 {@code %}, {@code _}, {@code \}가 와일드카드/이스케이프로 해석되지 않도록
 * 이스케이프 문자 {@code \}를 붙인다. 쿼리에는 {@code escape '\'}가 명시되어 있어야 한다.
 */
public final class LikePatternEscaper {

	private static final char ESCAPE_CHAR = '\\';

	private LikePatternEscaper() {
	}

	/** 입력 문자열의 LIKE 특수문자를 이스케이프한다. */
	public static String escapeLikePattern(String keyword) {
		if (keyword == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder(keyword.length() + 8);
		for (char ch : keyword.toCharArray()) {
			if (ch == ESCAPE_CHAR || ch == '%' || ch == '_') {
				builder.append(ESCAPE_CHAR);
			}
			builder.append(ch);
		}
		return builder.toString();
	}

	/** 부분 일치 검색용 {@code %keyword%} 패턴을 만든다. 호출자는 non-blank 키워드만 넘긴다. */
	public static String toContainsPattern(String keyword) {
		return "%" + escapeLikePattern(keyword.trim()) + "%";
	}
}
