package com.streaming.music.exception;

import org.springframework.http.HttpStatus;

/**
 * 공통 에러 코드 (PRD-05 §7.2). HTTP 상태와 기본 메시지를 함께 정의한다.
 */
public enum ErrorCode {

    INVALID_AUDIO_FORMAT(HttpStatus.BAD_REQUEST, "허용되지 않는 오디오 형식이거나 손상된 파일입니다."),
    INVALID_ARTWORK(HttpStatus.BAD_REQUEST, "앨범자켓은 10MB 이하의 JPEG 또는 PNG만 허용됩니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TRACK_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 음원입니다."),
    TRACK_NOT_READY(HttpStatus.CONFLICT, "음원 변환이 아직 완료되지 않았습니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "업로드 용량 한도를 초과했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
