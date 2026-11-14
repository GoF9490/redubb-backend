package com.streaming.music.global.exception;

import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 모든 API 오류를 공통 {@link ErrorResponse}로 변환하는 전역 예외 핸들러 (PRD-05 §7.2).
 * 500 응답에는 예외 상세·스택을 노출하지 않고 로그로만 기록한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** 업무 예외: 예외가 가진 ErrorCode와 메시지를 그대로 응답한다. */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
		log.warn("업무 예외 발생: {} - {}", ex.getErrorCode(), ex.getMessage());
		return toResponse(ex.getErrorCode(), ex.getMessage());
	}

	/** 잘못된 UUID 등 경로 변수/파라미터 타입 불일치. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		log.warn("파라미터 타입 불일치: {}", ex.getName());
		return toResponse(ErrorCode.INVALID_REQUEST, "요청 파라미터 형식이 올바르지 않습니다: " + ex.getName());
	}

	/** 필수 파라미터/파트 누락, 바디 파싱 실패, 검증 실패. */
	@ExceptionHandler({
			MissingServletRequestParameterException.class,
			MissingServletRequestPartException.class,
			MethodArgumentNotValidException.class,
			HandlerMethodValidationException.class,
			HttpMessageNotReadableException.class,
			ConstraintViolationException.class
	})
	public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception ex) {
		log.warn("잘못된 요청: {}", ex.getClass().getSimpleName());
		return toResponse(ErrorCode.INVALID_REQUEST);
	}

	/** 멀티파트 용량 초과. */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
		log.warn("멀티파트 용량 초과");
		return toResponse(ErrorCode.PAYLOAD_TOO_LARGE);
	}

	/**
	 * PRD 표에 없는 표준 MVC 예외(NoResourceFoundException, HttpRequestMethodNotSupportedException 등).
	 * 원래 HTTP 상태를 유지하고, 4xx는 INVALID_REQUEST, 5xx는 INTERNAL_ERROR 코드로 응답한다.
	 */
	@ExceptionHandler({ServletException.class, ErrorResponseException.class})
	public ResponseEntity<ErrorResponse> handleStandardMvcException(Exception ex) {
		HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
		// 우리 ErrorResponse record와 이름이 겹치므로 Spring 인터페이스는 FQN으로 구분한다
		if (ex instanceof org.springframework.web.ErrorResponse springError) {
			status = springError.getStatusCode();
		}
		if (status.is4xxClientError()) {
			log.warn("표준 MVC 예외: {} ({})", ex.getClass().getSimpleName(), status.value());
			return ResponseEntity.status(status).body(ErrorResponse.of(ErrorCode.INVALID_REQUEST));
		}
		log.error("처리되지 않은 서버 예외", ex);
		return toResponse(ErrorCode.INTERNAL_ERROR);
	}

	/** 그 외 모든 예외: 상세는 로그로만 남기고 고정 메시지를 응답한다. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
		log.error("처리되지 않은 서버 예외", ex);
		return toResponse(ErrorCode.INTERNAL_ERROR);
	}

	private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode) {
		return toResponse(errorCode, errorCode.getDefaultMessage());
	}

	private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode, String message) {
		return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode, message));
	}
}
