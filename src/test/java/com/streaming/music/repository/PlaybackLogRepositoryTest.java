package com.streaming.music.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.entity.ActionType;
import com.streaming.music.entity.PlaybackLog;
import com.streaming.music.entity.Track;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles({"local", "test"})
class PlaybackLogRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlaybackLogRepository playbackLogRepository;

    @Test
    @DisplayName("비로그인(user=null) 이력도 저장되고 조회된다")
    void saveAnonymousLog() {
        UUID trackId = UUID.randomUUID();
        Track track = entityManager.persist(Track.builder()
                .trackId(trackId).title("t").durationSeconds(10).originalFilename("a.mp3")
                .fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
                .originalS3Key("original/" + trackId + ".mp3").build());

        PlaybackLog saved = playbackLogRepository.saveAndFlush(
                PlaybackLog.builder().track(track).actionType(ActionType.STREAM).build());
        entityManager.clear();

        PlaybackLog found = playbackLogRepository.findById(saved.getLogId()).orElseThrow();
        assertThat(found.getUser()).isNull();
        assertThat(found.getActionType()).isEqualTo(ActionType.STREAM);
    }
}
