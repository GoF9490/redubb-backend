package com.streaming.music.exception;

/**
 * status가 READY가 아닌 트랙의 재생 진입점을 조회한 경우 (409).
 */
public class TrackNotReadyException extends BusinessException {

	public TrackNotReadyException() {
		super(ErrorCode.TRACK_NOT_READY);
	}

	public TrackNotReadyException(String message) {
		super(ErrorCode.TRACK_NOT_READY, message);
	}

	public TrackNotReadyException(String message, Throwable cause) {
		super(ErrorCode.TRACK_NOT_READY, message, cause);
	}
}
