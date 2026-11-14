package com.streaming.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streaming.music.config.properties.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JwtTokenProvider 단위 테스트. 실제 JJWT(jjwt-jackson 0.12.6 + Jackson 2)로 생성·파싱 왕복을 검증한다.
 */
class JwtTokenProviderTest {

	private static final String SECRET = "test-only-jwt-secret-key-at-least-32-chars-long";
	private static final String LONG_SECRET = "test-only-long-secret-key-for-hs512-0123456789-0123456789-abcdefghij";
	private static final long EXPIRATION_MS = 60_000L;
	private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

	/** 시간을 바꿀 수 있는 테스트용 Clock. */
	private static class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void setNow(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private JwtProperties properties(String secret) {
		return new JwtProperties(secret, EXPIRATION_MS, "http://localhost/auth/callback", List.of());
	}

	private JwtTokenProvider createProvider(String secret, Clock clock) {
		return new JwtTokenProvider(properties(secret), clock);
	}

	private String base64Url(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("정상 토큰은 생성 후 파싱하면 userId와 role이 일치한다")
	void parseTokenReturnsClaimsForValidToken() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));

		String token = provider.createToken(42L, "ROLE_USER");

		assertThat(provider.parseToken(token)).contains(new JwtClaims(42L, "ROLE_USER"));
	}

	@Test
	@DisplayName("토큰의 만료 시각은 발급 시각 + 설정된 만료 시간이다")
	void createTokenSetsExpirationFromClock() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));
		SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

		String token = provider.createToken(1L, "ROLE_ADMIN");
		var claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(BASE)).build()
				.parseSignedClaims(token);

		assertThat(claims.getPayload().getIssuedAt().toInstant()).isEqualTo(BASE);
		assertThat(claims.getPayload().getExpiration().toInstant()).isEqualTo(BASE.plusMillis(EXPIRATION_MS));
		assertThat(claims.getPayload().getSubject()).isEqualTo("1");
		assertThat(claims.getHeader().getAlgorithm()).isEqualTo("HS256");
	}

	@Test
	@DisplayName("만료 직전 토큰은 유효하고 만료 시각 이후 토큰은 거부된다")
	void parseTokenRejectsExpiredToken() {
		MutableClock clock = new MutableClock(BASE);
		JwtTokenProvider provider = createProvider(SECRET, clock);
		String token = provider.createToken(7L, "ROLE_USER");

		clock.setNow(BASE.plusMillis(EXPIRATION_MS - 1_000));
		assertThat(provider.parseToken(token)).isPresent();

		clock.setNow(BASE.plusMillis(EXPIRATION_MS + 1_000));
		assertThat(provider.parseToken(token)).isEmpty();
	}

	@Test
	@DisplayName("다른 키로 서명한 토큰은 거부된다")
	void parseTokenRejectsTokenSignedWithOtherKey() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));
		JwtTokenProvider otherProvider = createProvider("another-secret-key-which-is-long-enough-123456", new MutableClock(BASE));

		String forged = otherProvider.createToken(1L, "ROLE_ADMIN");

		assertThat(provider.parseToken(forged)).isEmpty();
	}

	@Test
	@DisplayName("payload를 변조한 토큰은 거부된다")
	void parseTokenRejectsTamperedPayload() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));
		String token = provider.createToken(1L, "ROLE_USER");
		String[] parts = token.split("\\.");
		long exp = BASE.plusMillis(EXPIRATION_MS).getEpochSecond();
		String tamperedPayload = base64Url("{\"sub\":\"1\",\"role\":\"ROLE_ADMIN\",\"iat\":" + BASE.getEpochSecond()
				+ ",\"exp\":" + exp + "}");

		String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

		assertThat(provider.parseToken(tampered)).isEmpty();
	}

	@Test
	@DisplayName("형식이 잘못된 토큰과 null, 빈 문자열은 예외 없이 empty를 반환한다")
	void parseTokenRejectsMalformedToken() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));

		assertThat(provider.parseToken("abc")).isEmpty();
		assertThat(provider.parseToken("a.b.c")).isEmpty();
		assertThat(provider.parseToken("..")).isEmpty();
		assertThat(provider.parseToken("")).isEmpty();
		assertThat(provider.parseToken("   ")).isEmpty();
		assertThat(provider.parseToken(null)).isEmpty();
	}

	@Test
	@DisplayName("alg=none 토큰과 서명 없는 토큰은 거부된다")
	void parseTokenRejectsUnsignedToken() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));
		long exp = BASE.plusMillis(EXPIRATION_MS).getEpochSecond();
		String noneToken = base64Url("{\"alg\":\"none\"}") + "."
				+ base64Url("{\"sub\":\"1\",\"role\":\"ROLE_ADMIN\",\"exp\":" + exp + "}") + ".";
		String unsecured = Jwts.builder().subject("1").claim("role", "ROLE_ADMIN")
				.expiration(Date.from(BASE.plusMillis(EXPIRATION_MS))).compact();

		assertThat(provider.parseToken(noneToken)).isEmpty();
		assertThat(provider.parseToken(unsecured)).isEmpty();
	}

	@Test
	@DisplayName("같은 키로 서명한 HS512 토큰도 HS256이 아니므로 거부된다")
	void parseTokenRejectsNonHs256Algorithm() {
		JwtTokenProvider provider = createProvider(LONG_SECRET, new MutableClock(BASE));
		SecretKey key = Keys.hmacShaKeyFor(LONG_SECRET.getBytes(StandardCharsets.UTF_8));
		String hs512Token = Jwts.builder().subject("1").claim("role", "ROLE_ADMIN")
				.issuedAt(Date.from(BASE)).expiration(Date.from(BASE.plusMillis(EXPIRATION_MS)))
				.signWith(key, Jwts.SIG.HS512).compact();

		// 같은 키로 발급한 HS256 토큰은 통과하여 키 설정이 올바름을 확인한다.
		assertThat(provider.parseToken(provider.createToken(1L, "ROLE_ADMIN"))).isPresent();
		assertThat(provider.parseToken(hs512Token)).isEmpty();
	}

	@Test
	@DisplayName("subject가 숫자가 아니거나 role 클레임이 없으면 거부된다")
	void parseTokenRejectsInvalidClaims() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));
		SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
		Date expiration = Date.from(BASE.plusMillis(EXPIRATION_MS));
		String nonNumericSubject = Jwts.builder().subject("abc").claim("role", "ROLE_USER")
				.expiration(expiration).signWith(key, Jwts.SIG.HS256).compact();
		String noRole = Jwts.builder().subject("1")
				.expiration(expiration).signWith(key, Jwts.SIG.HS256).compact();
		String noSubject = Jwts.builder().claim("role", "ROLE_USER")
				.expiration(expiration).signWith(key, Jwts.SIG.HS256).compact();

		assertThat(provider.parseToken(nonNumericSubject)).isEmpty();
		assertThat(provider.parseToken(noRole)).isEmpty();
		assertThat(provider.parseToken(noSubject)).isEmpty();
	}

	@Test
	@DisplayName("userId나 role이 비어 있으면 토큰 생성 시 예외가 발생하고 메시지에 값이 없다")
	void createTokenRejectsInvalidArguments() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));

		assertThatThrownBy(() -> provider.createToken(null, "ROLE_USER"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> provider.createToken(1L, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> provider.createToken(1L, "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("서명 키가 32바이트 미만이면 명확한 예외가 발생하고 키 값은 메시지에 없다")
	void constructorRejectsShortKey() {
		String shortKey = "a".repeat(31);

		assertThatThrownBy(() -> createProvider(shortKey, Clock.systemUTC()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32바이트")
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortKey));
		assertThatThrownBy(() -> createProvider(null, Clock.systemUTC()))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> createProvider("   ", Clock.systemUTC()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("정확히 32바이트 키는 허용되고 한글 키는 문자 수가 아닌 UTF-8 바이트 기준으로 판정한다")
	void constructorChecksKeyByteLength() {
		// 32바이트 ASCII
		JwtTokenProvider exact = createProvider("a".repeat(32), new MutableClock(BASE));
		assertThat(exact.parseToken(exact.createToken(1L, "ROLE_USER"))).isPresent();

		// 한글 11자 = 33바이트 (허용), 한글 10자 = 30바이트 (거부)
		JwtTokenProvider korean = createProvider("가".repeat(11), new MutableClock(BASE));
		assertThat(korean.parseToken(korean.createToken(1L, "ROLE_USER"))).isPresent();
		assertThatThrownBy(() -> createProvider("가".repeat(10), Clock.systemUTC()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("Optional 반환 타입으로 유효 토큰만 값을 가진다")
	void parseTokenReturnsOptional() {
		JwtTokenProvider provider = createProvider(SECRET, new MutableClock(BASE));

		Optional<JwtClaims> valid = provider.parseToken(provider.createToken(5L, "ROLE_ADMIN"));

		assertThat(valid).map(JwtClaims::role).contains("ROLE_ADMIN");
	}
}
