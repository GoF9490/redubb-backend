package com.streaming.music.global.exception;

/**
 * 인증 정보가 유효하지 않은 경우 (401). 예: 토큰은 유효하지만 회원이 존재하지 않음.
 */
public class UnauthorizedException extends BusinessException {

	public UnauthorizedException() {
		super(ErrorCode.UNAUTHORIZED);
	}

	public UnauthorizedException(String message) {
		super(ErrorCode.UNAUTHORIZED, message);
	}
}
