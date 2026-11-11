package com.streaming.music.service;

import com.streaming.music.dto.event.TranscodeEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 업로드 트랜잭션 커밋 직후 변환 이벤트를 받아 {@link TranscodeProcessor}에 넘기는 동기 릴레이.
 * <p>
 * {@code @Async} 거부({@link TaskRejectedException})는 AFTER_COMMIT 콜백에서 로그로만 사라져 트랙이
 * PENDING에 영구히 남고, 거부된 작업에는 trackId가 없어 FAILED로 바꿀 수 없다.
 * 그래서 trackId를 쥔 이 동기 릴레이에서 거부를 잡아 FAILED(TRANSCODE_QUEUE_FULL)로 기록한다.
 * <p>
 * {@link TranscodeProcessor} 구현체는 T4-2에서 생긴다. 그 전에도 컨텍스트가 기동되도록
 * {@link ObjectProvider}로 지연 주입하며, 부재 시에는 경고 로그만 남긴다.
 * T4-2에서 구현체가 생기면 필수 주입으로 전환할 예정이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeEventRelay {

    private final ObjectProvider<TranscodeProcessor> processorProvider;
    private final TranscodeRejectionHandler rejectionHandler;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void relayTranscode(TranscodeEventDto dto) {
        try {
            TranscodeProcessor processor = processorProvider.getIfAvailable();
            if (processor == null) {
                log.warn("TranscodeProcessor 빈이 없어 변환을 시작하지 못했습니다. trackId={}", dto.trackId());
                return;
            }
            processor.process(dto);
        } catch (TaskRejectedException e) {
            log.warn("변환 큐가 가득 차 작업이 거부되었습니다. trackId={}", dto.trackId());
            rejectionHandler.markQueueFull(dto.trackId());
        } catch (RuntimeException e) {
            log.error("변환 작업 제출 중 예외가 발생했습니다. trackId={}", dto.trackId(), e);
        }
    }
}
