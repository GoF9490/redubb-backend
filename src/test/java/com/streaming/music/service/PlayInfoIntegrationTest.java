package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streaming.music.dto.response.StreamPlayResponse;
import com.streaming.music.entity.ActionType;
import com.streaming.music.entity.PlaybackLog;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재생 진입점 + 이력 기록 통합 테스트 (H2, T5-3).
 *
 * <p>T1-4에서 이관된 항목(REQUIRES_NEW가 호출자 readOnly 트랜잭션과 무관하게 저장되고,
 * 저장 실패가 UnexpectedRollbackException을 일으키지 않음)을 확인한다.
 */
class PlayInfoIntegrationTest extends TranscodeIntegrationTestBase {

    /** 존재하지 않는 회원 ID (FK 위반 유도). */
    private static final long MISSING_USER_ID = 987_654_321L;

    @Autowired
    private TrackService trackService;

    @Autowired
    private PlaybackLogService playbackLogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long saveUser() {
        User user = userRepository.save(User.builder()
                .provider("GOOGLE").providerId("play-" + UUID.randomUUID()).email("p@example.com").build());
        createdUserIds.add(user.getUserId());
        return user.getUserId();
    }

    private TransactionTemplate readOnlyTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    @Test
    @DisplayName("비로그인 재생 시 응답이 반환되고 user=null인 STREAM 이력이 저장된다")
    void saveStreamLogForAnonymous() {
        UUID trackId = saveReadyTrack();

        StreamPlayResponse response = trackService.getPlayInfo(trackId, null);

        assertThat(response.trackId()).isEqualTo(trackId);
        assertThat(response.streamUrl()).endsWith("/stream/" + trackId + "/index.m3u8");
        List<PlaybackLog> logs = playbackLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionType()).isEqualTo(ActionType.STREAM);
        assertThat(logs.get(0).getUser()).isNull();
    }

    @Test
    @DisplayName("로그인 재생 시 user가 연결된 STREAM 이력이 저장된다")
    void saveStreamLogForUser() {
        UUID trackId = saveReadyTrack();
        Long userId = saveUser();

        trackService.getPlayInfo(trackId, userId);

        List<PlaybackLog> logs = playbackLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionType()).isEqualTo(ActionType.STREAM);
        assertThat(logs.get(0).getUser().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("호출자가 readOnly 트랜잭션이어도 이력이 저장되고 커밋 시 예외가 없다")
    void saveLogInsideReadOnlyTransaction() {
        UUID trackId = saveReadyTrack();
        Track track = trackRepository.findById(trackId).orElseThrow();
        Long userId = saveUser();

        assertThatCode(() -> readOnlyTemplate().executeWithoutResult(
                status -> playbackLogService.recordLog(track, userId, ActionType.STREAM)))
                .doesNotThrowAnyException();

        assertThat(playbackLogRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("이력 저장이 실패해도 응답은 정상이고 UnexpectedRollbackException이 없다")
    void returnResponseWhenLogFails() {
        UUID trackId = saveReadyTrack();

        StreamPlayResponse response = trackService.getPlayInfo(trackId, MISSING_USER_ID);

        assertThat(response.trackId()).isEqualTo(trackId);
        assertThat(playbackLogRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("readOnly 트랜잭션 안에서 이력 저장이 실패해도 호출자는 정상 커밋된다")
    void commitReadOnlyCallerWhenLogFails() {
        UUID trackId = saveReadyTrack();

        StreamPlayResponse response = readOnlyTemplate().execute(
                status -> trackService.getPlayInfo(trackId, MISSING_USER_ID));

        assertThat(response).isNotNull();
        assertThat(playbackLogRepository.findAll()).isEmpty();
    }
}
