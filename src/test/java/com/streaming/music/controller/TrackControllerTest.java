package com.streaming.music.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streaming.music.config.SecurityConfig;
import com.streaming.music.security.CustomOidcUserService;
import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.dto.response.DownloadInfo;
import com.streaming.music.dto.response.StreamPlayResponse;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.exception.TrackNotReadyException;
import com.streaming.music.exception.GlobalExceptionHandler;
import com.streaming.music.exception.InvalidAudioFormatException;
import com.streaming.music.exception.InvalidRequestException;
import com.streaming.music.exception.TrackNotFoundException;
import com.streaming.music.security.JwtTokenProvider;
import com.streaming.music.security.RestAuthenticationEntryPoint;
import com.streaming.music.service.MediaUrlResolver;
import com.streaming.music.service.TrackService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
@ActiveProfiles({"local", "test"})
class TrackControllerTest {

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

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

    private void stubEmptyPage() {
        when(trackService.getTracks(any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
    }

    @Test
    @DisplayName("파라미터가 없으면 기본값 page 0, size 20으로 서비스를 호출한다")
    void returnPageWithDefaults() throws Exception {
        stubEmptyPage();

        mockMvc.perform(get("/api/tracks")).andExpect(status().isOk());

        verify(trackService).getTracks(isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("쿼리 파라미터가 그대로 서비스에 전달된다")
    void passQueryParamsToService() throws Exception {
        stubEmptyPage();

        mockMvc.perform(get("/api/tracks").param("keyword", "kw").param("status", "READY")
                        .param("page", "2").param("size", "50"))
                .andExpect(status().isOk());

        verify(trackService).getTracks("kw", TrackStatus.READY, 2, 50);
    }

    @Test
    @DisplayName("size=500도 컨트롤러가 보정하지 않고 서비스에 전달한다")
    void passOversizedSizeUntouched() throws Exception {
        stubEmptyPage();

        mockMvc.perform(get("/api/tracks").param("size", "500")).andExpect(status().isOk());

        verify(trackService).getTracks(isNull(), isNull(), eq(0), eq(500));
    }

    @Test
    @DisplayName("빈 문자열 status/keyword는 400이 아니라 서비스에 전달된다")
    void passEmptyStringParams() throws Exception {
        stubEmptyPage();

        mockMvc.perform(get("/api/tracks").param("status", "").param("keyword", ""))
                .andExpect(status().isOk());

        verify(trackService).getTracks(eq(""), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("목록 응답은 고정 스키마 6개 키이며 항목에 errorMessage가 없다")
    void returnFixedPageSchema() throws Exception {
        Track track = createTrack();
        when(trackService.getTracks(any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(track), PageRequest.of(0, 1), 3));
        when(mediaUrlResolver.resolveArtworkUrl(track)).thenReturn("/artwork/k.jpg");

        mockMvc.perform(get("/api/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content[0].trackId").value(track.getTrackId().toString()))
                .andExpect(jsonPath("$.content[0].artworkUrl").value("/artwork/k.jpg"))
                .andExpect(jsonPath("$.content[0].errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("빈 페이지는 200과 빈 content를 반환한다")
    void returnEmptyPage() throws Exception {
        stubEmptyPage();

        mockMvc.perform(get("/api/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("잘못된 status 값(FOO, 소문자 ready)은 400 INVALID_REQUEST이다")
    void rejectInvalidStatus() throws Exception {
        for (String value : List.of("FOO", "ready")) {
            mockMvc.perform(get("/api/tracks").param("status", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        verifyNoInteractions(trackService);
    }

    @Test
    @DisplayName("숫자가 아닌 page/size는 400 INVALID_REQUEST이다")
    void rejectNonNumericPaging() throws Exception {
        mockMvc.perform(get("/api/tracks").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/tracks").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(trackService);
    }

    @Test
    @DisplayName("서비스의 InvalidRequestException은 400 INVALID_REQUEST이다")
    void rejectNegativePage() throws Exception {
        when(trackService.getTracks(any(), any(), anyInt(), anyInt())).thenThrow(new InvalidRequestException());

        mockMvc.perform(get("/api/tracks").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("상세 조회는 200과 11개 필드를 반환하고 errorMessage를 노출하지 않는다")
    void returnTrackDetail() throws Exception {
        Track track = createTrack();
        when(trackService.getTrack(track.getTrackId())).thenReturn(track);
        when(mediaUrlResolver.resolveArtworkUrl(track)).thenReturn("/artwork/k.jpg");

        mockMvc.perform(get("/api/tracks/{trackId}", track.getTrackId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(11)))
                .andExpect(jsonPath("$.trackId").value(track.getTrackId().toString()))
                .andExpect(jsonPath("$.artworkUrl").value("/artwork/k.jpg"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-11T01:00:00Z"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("잘못된 UUID 경로(GET /upload 포함)는 400 INVALID_REQUEST이고 서비스를 호출하지 않는다")
    void rejectInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/tracks/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/tracks/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(trackService);
    }

    @Test
    @DisplayName("재생 진입점은 200과 6개 필드를 반환하고 userId는 null로 전달된다")
    void returnPlayInfo() throws Exception {
        UUID trackId = UUID.randomUUID();
        when(trackService.getPlayInfo(eq(trackId), isNull())).thenReturn(new StreamPlayResponse(
                trackId, "/stream/x/index.m3u8", "제목", "가수", 215, "/artwork/x.jpg"));

        mockMvc.perform(get("/api/tracks/{trackId}/play", trackId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.trackId").value(trackId.toString()))
                .andExpect(jsonPath("$.streamUrl").value("/stream/x/index.m3u8"))
                .andExpect(jsonPath("$.title").value("제목"))
                .andExpect(jsonPath("$.artist").value("가수"))
                .andExpect(jsonPath("$.durationSeconds").value(215))
                .andExpect(jsonPath("$.artworkUrl").value("/artwork/x.jpg"));
    }

    @Test
    @DisplayName("미완료 트랙의 재생 진입점은 409 TRACK_NOT_READY이다")
    void rejectPlayOfNotReadyTrack() throws Exception {
        UUID trackId = UUID.randomUUID();
        when(trackService.getPlayInfo(eq(trackId), any())).thenThrow(new TrackNotReadyException());

        mockMvc.perform(get("/api/tracks/{trackId}/play", trackId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_READY"));
    }

    @Test
    @DisplayName("없는 트랙의 재생 진입점은 404, 잘못된 UUID는 400이다")
    void rejectPlayOfMissingOrInvalidTrack() throws Exception {
        UUID trackId = UUID.randomUUID();
        when(trackService.getPlayInfo(eq(trackId), any())).thenThrow(new TrackNotFoundException());

        mockMvc.perform(get("/api/tracks/{trackId}/play", trackId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
        mockMvc.perform(get("/api/tracks/not-a-uuid/play"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("다운로드는 200, 빈 본문, X-Accel-Redirect·Content-Disposition·Content-Type 헤더를 반환한다")
    void returnDownloadHeaders() throws Exception {
        UUID trackId = UUID.randomUUID();
        String redirect = "/internal-storage/original/2026/11/k.mp3?X-Amz-Signature=sig%2F1";
        String disposition = "attachment; filename=\"a.mp3\"; filename*=UTF-8''a.mp3";
        when(trackService.getDownloadInfo(eq(trackId), isNull()))
                .thenReturn(new DownloadInfo(redirect, disposition, "audio/mpeg"));

        mockMvc.perform(get("/api/tracks/{trackId}/download", trackId))
                .andExpect(status().isOk())
                .andExpect(content().string(""))
                .andExpect(header().string("X-Accel-Redirect", redirect))
                .andExpect(header().string("Content-Disposition", disposition))
                .andExpect(header().string("Content-Type", "audio/mpeg"));
    }

    @Test
    @DisplayName("없는 트랙의 다운로드는 404, 잘못된 UUID는 400이다")
    void rejectDownloadOfMissingOrInvalidTrack() throws Exception {
        UUID trackId = UUID.randomUUID();
        when(trackService.getDownloadInfo(eq(trackId), any())).thenThrow(new TrackNotFoundException());

        mockMvc.perform(get("/api/tracks/{trackId}/download", trackId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
        mockMvc.perform(get("/api/tracks/not-a-uuid/download"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("존재하지 않는 트랙은 404 TRACK_NOT_FOUND이다")
    void returnNotFoundForMissingTrack() throws Exception {
        UUID trackId = UUID.randomUUID();
        when(trackService.getTrack(trackId)).thenThrow(new TrackNotFoundException());

        mockMvc.perform(get("/api/tracks/{trackId}", trackId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }
}
