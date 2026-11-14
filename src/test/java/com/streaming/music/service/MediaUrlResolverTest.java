package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.config.properties.MediaProperties;
import com.streaming.music.entity.Track;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MediaUrlResolverTest {

	private static final UUID TRACK_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	private MediaUrlResolver createResolver(String baseUrl) {
		return new MediaUrlResolver(new MediaProperties(baseUrl));
	}

	private Track createTrack(String artworkS3Key, String hlsPlaylistKey) {
		return Track.builder()
				.trackId(TRACK_ID)
				.artworkS3Key(artworkS3Key)
				.hlsPlaylistKey(hlsPlaylistKey)
				.build();
	}

	private Track createReadyTrack() {
		return createTrack("artwork/" + TRACK_ID + ".jpg", "hls/" + TRACK_ID + "/index.m3u8");
	}

	@Test
	@DisplayName("base가 빈 값이면 streamUrl은 상대경로다")
	void resolveStreamUrlBaseEmpty() {
		assertThat(createResolver("").resolveStreamUrl(createReadyTrack()))
				.isEqualTo("/stream/" + TRACK_ID + "/index.m3u8");
	}

	@Test
	@DisplayName("base가 null이면 빈 값과 동일하게 상대경로를 반환한다")
	void resolveUrlNullBase() {
		MediaUrlResolver resolver = createResolver(null);
		assertThat(resolver.resolveStreamUrl(createReadyTrack()))
				.isEqualTo("/stream/" + TRACK_ID + "/index.m3u8");
		assertThat(resolver.resolveArtworkUrl(createReadyTrack()))
				.isEqualTo("/artwork/" + TRACK_ID + ".jpg");
	}

	@Test
	@DisplayName("base가 있으면 streamUrl 앞에 base가 붙는다")
	void resolveStreamUrlWithBase() {
		assertThat(createResolver("https://media.example.com").resolveStreamUrl(createReadyTrack()))
				.isEqualTo("https://media.example.com/stream/" + TRACK_ID + "/index.m3u8");
	}

	@Test
	@DisplayName("hlsPlaylistKey가 null이면 streamUrl은 null이다")
	void resolveStreamUrlNullPlaylistKey() {
		assertThat(createResolver("").resolveStreamUrl(createTrack(null, null))).isNull();
	}

	@Test
	@DisplayName("artworkS3Key가 null이면 artworkUrl은 null이다")
	void resolveArtworkUrlNullKey() {
		assertThat(createResolver("https://media.example.com")
				.resolveArtworkUrl(createTrack(null, null))).isNull();
	}

	@Test
	@DisplayName("artworkS3Key가 있으면 base를 적용한 artworkUrl을 반환한다")
	void resolveArtworkUrlWithBase() {
		assertThat(createResolver("https://media.example.com").resolveArtworkUrl(createReadyTrack()))
				.isEqualTo("https://media.example.com/artwork/" + TRACK_ID + ".jpg");
		assertThat(createResolver("").resolveArtworkUrl(createReadyTrack()))
				.isEqualTo("/artwork/" + TRACK_ID + ".jpg");
	}

	@Test
	@DisplayName("base 끝에 슬래시가 있어도 // 중복이 없다")
	void resolveUrlTrailingSlashBase() {
		for (String base : new String[] {"https://media.example.com/", "https://media.example.com///"}) {
			MediaUrlResolver resolver = createResolver(base);
			assertThat(resolver.resolveStreamUrl(createReadyTrack()))
					.isEqualTo("https://media.example.com/stream/" + TRACK_ID + "/index.m3u8");
			assertThat(resolver.resolveArtworkUrl(createReadyTrack()))
					.isEqualTo("https://media.example.com/artwork/" + TRACK_ID + ".jpg");
		}
	}
}
