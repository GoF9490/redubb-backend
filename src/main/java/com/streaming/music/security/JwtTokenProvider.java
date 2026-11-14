package com.streaming.music.security;

import com.streaming.music.config.properties.JwtProperties;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 JWT 생성·파싱·검증 (sub=userId, role 클레임).
 * 무효 토큰은 예외 대신 Optional.empty()로 반환하며, 로그에는 토큰·키·클레임 값을 남기지 않는다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

	private static final int MIN_KEY_BYTES = 32;
	private static final String ROLE_CLAIM = "role";
	private static final String EXPECTED_ALGORITHM = Jwts.SIG.HS256.getId();

	private final SecretKey signingKey;
	private final long accessTokenExpirationMs;
	private final Clock clock;
	private final JwtParser parser;

	public JwtTokenProvider(JwtProperties jwtProperties, Clock clock) {
		String secretKey = jwtProperties.secretKey();
		if (secretKey == null || secretKey.isBlank()) {
			throw new IllegalStateException("app.jwt.secret-key가 설정되지 않았습니다.");
		}
		byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < MIN_KEY_BYTES) {
			throw new IllegalStateException("app.jwt.secret-key는 UTF-8 기준 최소 32바이트(256비트)여야 합니다.");
		}
		this.signingKey = Keys.hmacShaKeyFor(keyBytes);
		this.accessTokenExpirationMs = jwtProperties.accessTokenExpirationMs();
		this.clock = clock;
		// 파서는 불변·스레드 안전하므로 한 번만 생성해 재사용한다. 만료 판단은 주입된 Clock 기준이다.
		this.parser = Jwts.parser()
				.verifyWith(signingKey)
				.clock(() -> Date.from(Instant.now(clock)))
				.build();
	}

	/** 액세스 토큰을 발급한다. */
	public String createToken(Long userId, String role) {
		if (userId == null) {
			throw new IllegalArgumentException("userId는 필수입니다.");
		}
		if (role == null || role.isBlank()) {
			throw new IllegalArgumentException("role은 필수입니다.");
		}
		Instant now = Instant.now(clock);
		return Jwts.builder()
				.subject(userId.toString())
				.claim(ROLE_CLAIM, role)
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plusMillis(accessTokenExpirationMs)))
				.signWith(signingKey, Jwts.SIG.HS256)
				.compact();
	}

	/** 토큰을 검증하고 클레임을 반환한다. 무효(만료·위조·형식 오류 등)이면 empty를 반환한다. */
	public Optional<JwtClaims> parseToken(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		try {
			Jws<Claims> jws = parser.parseSignedClaims(token);
			if (!EXPECTED_ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
				return rejectWarn("UNSUPPORTED");
			}
			Claims claims = jws.getPayload();
			String subject = claims.getSubject();
			String role = claims.get(ROLE_CLAIM, String.class);
			if (subject == null || role == null || role.isBlank()) {
				return rejectWarn("INVALID_CLAIMS");
			}
			return Optional.of(new JwtClaims(Long.parseLong(subject), role));
		} catch (ExpiredJwtException e) {
			log.debug("JWT 검증 실패: reason=EXPIRED");
			return Optional.empty();
		} catch (SignatureException e) {
			return rejectWarn("INVALID_SIGNATURE");
		} catch (MalformedJwtException e) {
			return rejectWarn("MALFORMED");
		} catch (UnsupportedJwtException e) {
			return rejectWarn("UNSUPPORTED");
		} catch (JwtException | IllegalArgumentException e) {
			// 숫자가 아닌 subject(NumberFormatException)와 그 외 클레임 오류 포함
			return rejectWarn("INVALID_CLAIMS");
		}
	}

	private Optional<JwtClaims> rejectWarn(String reason) {
		log.warn("JWT 검증 실패: reason={}", reason);
		return Optional.empty();
	}
}
