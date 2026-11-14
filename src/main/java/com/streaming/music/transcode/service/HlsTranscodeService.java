package com.streaming.music.transcode.service;

import com.streaming.music.transcode.dto.TranscodeEventDto;

/**
 * 변환 작업 실행 계약.
 * <p>
 * 구현체({@code HlsTranscodeServiceImpl})는 {@code @Async("transcodeExecutor")}를 선언한다.
 * 큐가 가득 차면 프록시 호출 시점에 {@code TaskRejectedException}이 호출자에게 던져진다.
 */
public interface HlsTranscodeService {

	void process(TranscodeEventDto dto);
}
