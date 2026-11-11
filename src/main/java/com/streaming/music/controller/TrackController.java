package com.streaming.music.controller;

import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.dto.response.StreamPlayResponse;
import com.streaming.music.dto.response.TrackPageResponse;
import com.streaming.music.dto.response.TrackResponse;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.service.MediaUrlResolver;
import com.streaming.music.service.TrackService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 음원 API 컨트롤러 (PRD-05 §7.1). */
@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    private final TrackService trackService;
    private final MediaUrlResolver mediaUrlResolver;

    public TrackController(TrackService trackService, MediaUrlResolver mediaUrlResolver) {
        this.trackService = trackService;
        this.mediaUrlResolver = mediaUrlResolver;
    }

    /** 음원 단건 업로드. file 누락 시 Spring이 MissingServletRequestPartException을 던져 400이 된다. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TrackResponse> uploadTrack(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "artworkFile", required = false) MultipartFile artworkFile,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String artist,
            @RequestParam(required = false) String album) {
        TrackUploadRequest request = new TrackUploadRequest(file, title, artist, album, artworkFile);
        Track track = trackService.upload(request, resolveUserId());
        return ResponseEntity.accepted().body(TrackResponse.from(track, mediaUrlResolver));
    }

    /**
     * 음원 목록·키워드 검색 조회 (최신순).
     *
     * <p>읽기 전용 공개 조회로 인증(T6-7)과 무관하다. page/size 보정과 검증은 서비스 책임이므로 그대로 전달한다.
     */
    @GetMapping
    public TrackPageResponse getTracks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) TrackStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return TrackPageResponse.from(trackService.getTracks(keyword, status, page, size), mediaUrlResolver);
    }

    /** 음원 단건 상세·상태 조회. 읽기 전용 공개 조회로 인증(T6-7)과 무관하다. */
    @GetMapping("/{trackId}")
    public TrackResponse getTrack(@PathVariable UUID trackId) {
        return TrackResponse.from(trackService.getTrack(trackId), mediaUrlResolver);
    }

    /** 재생 진입점 조회. READY가 아니면 409, 없으면 404. 성공 시 STREAM 이력이 기록된다. */
    @GetMapping("/{trackId}/play")
    public StreamPlayResponse getPlayInfo(@PathVariable UUID trackId) {
        return trackService.getPlayInfo(trackId, resolveUserId());
    }

    /**
     * 요청 사용자의 userId를 반환한다.
     *
     * <p>T6-7 연결 지점: 인증 연결 전이므로 현재는 항상 null(비로그인 업로드)이다.
     * T6-7에서 SecurityContext의 Principal(Long userId)을 변환하도록 이 메서드만 교체한다.
     */
    private Long resolveUserId() {
        return null;
    }
}
