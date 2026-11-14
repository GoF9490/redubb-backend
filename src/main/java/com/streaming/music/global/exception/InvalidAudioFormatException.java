package com.streaming.music.global.exception;

/**
 * 허용되지 않는 오디오 MIME이거나 헤더 파싱에 실패한 경우 (400).
 */
public class InvalidAudioFormatException extends BusinessException {

	public InvalidAudioFormatException() {
		super(ErrorCode.INVALID_AUDIO_FORMAT);
	}

	public InvalidAudioFormatException(String message) {
		super(ErrorCode.INVALID_AUDIO_FORMAT, message);
	}

	public InvalidAudioFormatException(String message, Throwable cause) {
		super(ErrorCode.INVALID_AUDIO_FORMAT, message, cause);
	}
}
