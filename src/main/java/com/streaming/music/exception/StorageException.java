package com.streaming.music.exception;

/**
 * 스토리지(RustFS/S3) 연산 실패를 나타내는 도메인 예외.
 * {@link ErrorCode#INTERNAL_ERROR}(500)로 응답되며, SDK 상세·서명 URL은 메시지에 담지 않고 cause로만 보존한다.
 */
public class StorageException extends BusinessException {

    public StorageException(String operation, Throwable cause) {
        super(ErrorCode.INTERNAL_ERROR, "스토리지 작업에 실패했습니다: " + operation, cause);
    }
}
