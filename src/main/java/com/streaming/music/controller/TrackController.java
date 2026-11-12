package com.streaming.music.controller;

import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.dto.response.DownloadInfo;
import com.streaming.music.dto.response.StreamPlayResponse;
import com.streaming.music.dto.response.TrackPageResponse;
import com.streaming.music.dto.response.TrackResponse;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.security.AuthenticatedUser;
import com.streaming.music.service.MediaUrlResolver;
import com.streaming.music.service.TrackService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "artist", required = false) String artist,
            @RequestParam(name = "album", required = false) String album,
            @AuthenticationPrincipal AuthenticatedUser user) {
        TrackUploadRequest request = new TrackUploadRequest(file, title, artist, album, artworkFile);
        Track track = trackService.upload(request, resolveUserId(user));
        return ResponseEntity.accepted().body(TrackResponse.from(track, mediaUrlResolver));
    }

    /**
     * 음원 목록·키워드 검색 조회 (최신순).
     *
     * <p>읽기 전용 공개 조회로 인증(T6-7)과 무관하다. page/size 보정과 검증은 서비스 책임이므로 그대로 전달한다.
     */
    @GetMapping
    public TrackPageResponse getTracks(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) TrackStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return TrackPageResponse.from(trackService.getTracks(keyword, status, page, size), mediaUrlResolver);
    }

    /** 음원 단건 상세·상태 조회. 읽기 전용 공개 조회로 인증(T6-7)과 무관하다. */
    @GetMapping("/{trackId}")
    public TrackResponse getTrack(@PathVariable("trackId") UUID trackId) {
        return TrackResponse.from(trackService.getTrack(trackId), mediaUrlResolver);
    }

    /** 재생 진입점 조회. READY가 아니면 409, 없으면 404. 성공 시 STREAM 이력이 기록된다. */
    @GetMapping("/{trackId}/play")
    public StreamPlayResponse getPlayInfo(
            @PathVariable("trackId") UUID trackId, @AuthenticationPrincipal AuthenticatedUser user) {
        return trackService.getPlayInfo(trackId, resolveUserId(user));
    }

    /**
     * 원본 다운로드 지시. 본문 없이 200과 X-Accel-Redirect·Content-Disposition·Content-Type 헤더만 반환한다.
     *
     * <p>Nginx의 헤더 유지와 presigned 쿼리 보존의 실제 확인은 통합 테스트(T7-3) 소관이다.
     */
    @GetMapping("/{trackId}/download")
    public ResponseEntity<Void> getDownloadInfo(
            @PathVariable("trackId") UUID trackId, @AuthenticationPrincipal AuthenticatedUser user) {
        DownloadInfo info = trackService.getDownloadInfo(trackId, resolveUserId(user));
        return ResponseEntity.ok()
                .header("X-Accel-Redirect", info.xAccelRedirectUri())
                .header(HttpHeaders.CONTENT_DISPOSITION, info.contentDisposition())
                .header(HttpHeaders.CONTENT_TYPE, info.contentType())
                .build();
    }

    /**
     * 인증된 사용자의 userId를 반환한다. 토큰이 없거나 무효이면 익명이므로 null이다.
     *
     * <p>요청 파라미터로 userId를 받지 않으며 JWT 필터가 검증한 Principal만 신뢰한다.
     * 존재하지 않는 userId(삭제된 회원)는 서비스가 익명으로 대체한다.
     */
    private Long resolveUserId(AuthenticatedUser user) {
        return user == null ? null : user.userId();
    }
}
