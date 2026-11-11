package com.streaming.music.service;

import com.streaming.music.entity.Track;
import com.streaming.music.repository.TrackRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변환 큐 포화로 거부된 트랙을 FAILED("TRANSCODE_QUEUE_FULL")로 기록한다.
 * <p>
 * 커밋 후 콜백 시점에는 완료된 트랜잭션의 리소스가 아직 바인딩되어 있을 수 있어, 독립적으로 반영되도록
 * {@code REQUIRES_NEW} 트랜잭션에서 갱신한다(통합 테스트로 DB 반영 확인). 갱신 실패는 로그만 남기고 삼킨다.
 */
@Slf4j
@Component
public class TranscodeRejectionHandler {

    public static final String QUEUE_FULL_MESSAGE = "TRANSCODE_QUEUE_FULL";

    private final TrackRepository trackRepository;
    private final TransactionTemplate requiresNewTemplate;

    public TranscodeRejectionHandler(TrackRepository trackRepository,
                                     PlatformTransactionManager transactionManager) {
        this.trackRepository = trackRepository;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void markQueueFull(UUID trackId) {
        try {
            requiresNewTemplate.executeWithoutResult(status -> {
                Optional<Track> found = trackRepository.findById(trackId);
                if (found.isEmpty()) {
                    log.warn("큐 포화 처리 대상 트랙이 없습니다. trackId={}", trackId);
                    return;
                }
                Track track = found.get();
                track.markFailed(QUEUE_FULL_MESSAGE);
                trackRepository.save(track);
            });
        } catch (Exception e) {
            log.error("큐 포화 트랙 FAILED 갱신에 실패했습니다. trackId={}", trackId, e);
        }
    }
}
