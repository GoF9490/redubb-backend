package com.streaming.music.exception;

/**
 * 앨범자켓이 JPEG/PNG가 아니거나 크기 제한을 초과한 경우 (400).
 */
public class InvalidArtworkException extends BusinessException {

	public InvalidArtworkException() {
		super(ErrorCode.INVALID_ARTWORK);
	}

	public InvalidArtworkException(String message) {
		super(ErrorCode.INVALID_ARTWORK, message);
	}

	public InvalidArtworkException(String message, Throwable cause) {
		super(ErrorCode.INVALID_ARTWORK, message, cause);
	}
}
