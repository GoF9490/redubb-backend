package com.streaming.music.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streaming.music.config.SecurityConfig;
import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.exception.GlobalExceptionHandler;
import com.streaming.music.exception.InvalidAudioFormatException;
import com.streaming.music.service.MediaUrlResolver;
import com.streaming.music.service.TrackService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@WebMvcTest(controllers = TrackController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles({"local", "test"})
class TrackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrackService trackService;

    @MockitoBean
    private MediaUrlResolver mediaUrlResolver;

    private final MockMultipartFile audio =
            new MockMultipartFile("file", "a.mp3", "audio/mpeg", new byte[]{1, 2, 3});

    private Track createTrack() {
        return Track.builder()
                .trackId(UUID.randomUUID()).title("제목").artist("가수").album("앨범")
                .durationSeconds(10).originalFilename("a.mp3").fileExtension("mp3")
                .fileSizeBytes(3L).mimeType("audio/mpeg").originalS3Key("original/k.mp3")
                .artworkS3Key("artwork/k.jpg").status(TrackStatus.PENDING)
                .errorMessage("내부 메시지")
                .createdAt(Instant.parse("2026-11-11T01:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("정상 업로드는 202와 PENDING TrackResponse를 반환하고 errorMessage를 노출하지 않는다")
    void acceptValidUpload() throws Exception {
        Track track = createTrack();
        when(trackService.upload(any(), isNull())).thenReturn(track);
        when(mediaUrlResolver.resolveArtworkUrl(track)).thenReturn("/artwork/k.jpg");

        mockMvc.perform(multipart("/api/tracks/upload").file(audio).param("title", "제목"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trackId").value(track.getTrackId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.artworkUrl").value("/artwork/k.jpg"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-11T01:00:00Z"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("요청 필드가 TrackUploadRequest로 서비스에 전달되고 userId는 null이다")
    void passRequestFieldsToService() throws Exception {
        when(trackService.upload(any(), isNull())).thenReturn(createTrack());
        MockMultipartFile art = new MockMultipartFile("artworkFile", "c.png", "image/png", new byte[]{9});

        mockMvc.perform(multipart("/api/tracks/upload").file(audio).file(art)
                        .param("title", "T").param("artist", "A").param("album", "B"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<TrackUploadRequest> captor = ArgumentCaptor.forClass(TrackUploadRequest.class);
        verify(trackService).upload(captor.capture(), isNull());
        TrackUploadRequest request = captor.getValue();
        assertThat(request.title()).isEqualTo("T");
        assertThat(request.artist()).isEqualTo("A");
        assertThat(request.album()).isEqualTo("B");
        assertThat(request.file().getOriginalFilename()).isEqualTo("a.mp3");
        assertThat(request.artworkFile().getOriginalFilename()).isEqualTo("c.png");
    }

    @Test
    @DisplayName("선택 필드 없이 file만 보내면 null로 전달된다")
    void passNullForOptionalFields() throws Exception {
        when(trackService.upload(any(), isNull())).thenReturn(createTrack());

        mockMvc.perform(multipart("/api/tracks/upload").file(audio)).andExpect(status().isAccepted());

        ArgumentCaptor<TrackUploadRequest> captor = ArgumentCaptor.forClass(TrackUploadRequest.class);
        verify(trackService).upload(captor.capture(), isNull());
        assertThat(captor.getValue().title()).isNull();
        assertThat(captor.getValue().artworkFile()).isNull();
    }

    @Test
    @DisplayName("file 누락은 400 INVALID_REQUEST이고 서비스를 호출하지 않는다")
    void rejectMissingFile() throws Exception {
        mockMvc.perform(multipart("/api/tracks/upload").param("title", "T"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(trackService);
    }

    @Test
    @DisplayName("서비스의 InvalidAudioFormatException은 400 INVALID_AUDIO_FORMAT이다")
    void rejectInvalidAudioFormat() throws Exception {
        when(trackService.upload(any(), any())).thenThrow(new InvalidAudioFormatException());

        mockMvc.perform(multipart("/api/tracks/upload").file(audio))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO_FORMAT"));
    }

    @Test
    @DisplayName("용량 초과는 413 PAYLOAD_TOO_LARGE이다")
    void rejectOversizedUpload() throws Exception {
        when(trackService.upload(any(), any())).thenThrow(new MaxUploadSizeExceededException(1L));

        mockMvc.perform(multipart("/api/tracks/upload").file(audio))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
}
