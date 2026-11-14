package com.streaming.music.service;

/**
 * FFmpeg 실행 결과.
 *
 * <p>계약: {@code logSummary}는 항상 500자 이하이며(호출자가 {@code error_message}로 그대로 기록 가능),
 * 이 클래스는 비밀 값이나 실행 파일 경로를 직접 넣지 않는다. 다만 FFmpeg 출력 자체에 작업 디렉터리 경로가
 * 포함될 수 있으므로 API 응답에는 노출하지 않아야 한다(TrackResponse는 errorMessage 미노출).
 * 타임아웃은 {@code exitCode = -1}이다.
 * 호출자(HlsTranscodeService)가 {@link Status#TIMEOUT}을 {@code FFMPEG_TIMEOUT}으로 매핑한다.
 */
public record FfmpegResult(Status status, int exitCode, String logSummary) {

	/** 로그 요약 최대 길이(문자). */
	public static final int MAX_SUMMARY_LENGTH = 500;

	public enum Status {
		SUCCESS,
		FAILED,
		TIMEOUT
	}

	public FfmpegResult {
		if (logSummary == null) {
			logSummary = "";
		} else if (logSummary.length() > MAX_SUMMARY_LENGTH) {
			// 마지막 로그가 중요하므로 뒤쪽을 보존한다
			logSummary = logSummary.substring(logSummary.length() - MAX_SUMMARY_LENGTH);
		}
	}

	public static FfmpegResult success(String logSummary) {
		return new FfmpegResult(Status.SUCCESS, 0, logSummary);
	}

	public static FfmpegResult failed(int exitCode, String logSummary) {
		return new FfmpegResult(Status.FAILED, exitCode, logSummary);
	}

	public static FfmpegResult timeout(String logSummary) {
		return new FfmpegResult(Status.TIMEOUT, -1, logSummary);
	}

	public boolean isSuccess() {
		return status == Status.SUCCESS;
	}
}
