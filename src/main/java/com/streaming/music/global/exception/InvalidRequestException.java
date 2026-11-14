package com.streaming.music.global.exception;

/**
 * 요청 값이 유효하지 않은 경우 (400, INVALID_REQUEST).
 */
public class InvalidRequestException extends BusinessException {

	public InvalidRequestException() {
		super(ErrorCode.INVALID_REQUEST);
	}

	public InvalidRequestException(String message) {
		super(ErrorCode.INVALID_REQUEST, message);
	}

	public InvalidRequestException(String message, Throwable cause) {
		super(ErrorCode.INVALID_REQUEST, message, cause);
	}
}
