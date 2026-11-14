package com.streaming.music.exception;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 공통 에러 응답 본문 (PRD-05 §7.2). timestamp는 Asia/Seoul(+09:00) ISO-8601로 직렬화된다.
 */
public record ErrorResponse(String code, String message, OffsetDateTime timestamp) {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message,
				OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS));
	}

	public static ErrorResponse of(ErrorCode errorCode) {
		return of(errorCode, errorCode.getDefaultMessage());
	}
}
