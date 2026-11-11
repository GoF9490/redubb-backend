package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.streaming.music.dto.response.DownloadInfo;
import com.streaming.music.entity.ActionType;
import com.streaming.music.entity.PlaybackLog;
import com.streaming.music.entity.User;
import com.streaming.music.exception.StorageException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 원본 다운로드 지시 + DOWNLOAD 이력 통합 테스트 (H2, T5-4). 스토리지는 Mock이다. */
class DownloadIntegrationTest extends TranscodeIntegrationTestBase {

    private static final long MISSING_USER_ID = 987_654_321L;

    @Autowired
    private TrackService trackService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void stubPresign() {
        when(storageService.createPresignedGetUrl(anyString(), anyInt())).thenAnswer(invocation ->
                "http://rustfs:9000/audio-storage/" + invocation.getArgument(0) + "?X-Amz-Signature=sig%2F1");
    }

    private Long saveUser() {
        User user = userRepository.save(User.builder()
                .provider("GOOGLE").providerId("dl-" + UUID.randomUUID()).email("d@example.com").build());
        createdUserIds.add(user.getUserId());
        return user.getUserId();
    }

    private TransactionTemplate readOnlyTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    @Test
    @DisplayName("PENDING 트랙도 다운로드되고 비로그인 DOWNLOAD 이력(user=null)이 저장된다")
    void saveDownloadLogForAnonymous() {
        UUID trackId = savePendingTrack();

        DownloadInfo info = trackService.getDownloadInfo(trackId, null);

        assertThat(info.xAccelRedirectUri()).isEqualTo(
                "/internal-storage/" + originalKeyOf(trackId) + "?X-Amz-Signature=sig%2F1");
        List<PlaybackLog> logs = playbackLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionType()).isEqualTo(ActionType.DOWNLOAD);
        assertThat(logs.get(0).getUser()).isNull();
    }

    @Test
    @DisplayName("로그인 다운로드 시 user가 연결된 DOWNLOAD 이력이 저장된다")
    void saveDownloadLogForUser() {
        UUID trackId = saveReadyTrack();
        Long userId = saveUser();

        trackService.getDownloadInfo(trackId, userId);

        List<PlaybackLog> logs = playbackLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionType()).isEqualTo(ActionType.DOWNLOAD);
        assertThat(logs.get(0).getUser().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("readOnly 트랜잭션 안에서 이력 저장이 실패해도 응답은 정상이고 호출자는 정상 커밋된다")
    void commitReadOnlyCallerWhenLogFails() {
        UUID trackId = saveReadyTrack();

        DownloadInfo info = readOnlyTemplate().execute(
                status -> trackService.getDownloadInfo(trackId, MISSING_USER_ID));

        assertThat(info).isNotNull();
        assertThat(playbackLogRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("presigned URL 생성이 실패하면 예외가 전파되고 이력은 0건이다")
    void skipLogWhenPresignFails() {
        UUID trackId = saveReadyTrack();
        when(storageService.createPresignedGetUrl(anyString(), anyInt())).thenThrow(new StorageException("presign"));

        assertThatThrownBy(() -> trackService.getDownloadInfo(trackId, null))
                .isInstanceOf(StorageException.class);

        assertThat(playbackLogRepository.findAll()).isEmpty();
    }
}
