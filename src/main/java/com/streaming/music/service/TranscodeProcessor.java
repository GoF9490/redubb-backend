package com.streaming.music.service;

import com.streaming.music.dto.event.TranscodeEventDto;

/**
 * 변환 작업 실행 계약.
 * <p>
 * 구현체(T4-2 {@code HlsTranscodeService})는 {@code @Async("transcodeExecutor")}를 선언한다.
 * 큐가 가득 차면 프록시 호출 시점에 {@code TaskRejectedException}이 호출자에게 던져진다.
 */
public interface TranscodeProcessor {

    void process(TranscodeEventDto dto);
}
