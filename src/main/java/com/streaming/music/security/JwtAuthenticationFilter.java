package com.streaming.music.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization 헤더의 Bearer 토큰을 검증하는 필터.
 * 토큰이 없거나 무효이거나 Bearer 형식이 아니면 아무것도 설정하지 않고 익명으로 통과시킨다
 * (401 판단은 AuthenticationEntryPoint가 인증 필수 경로에서만 수행한다).
 *
 * <p>빈(@Component)으로 등록하지 않는다. 서블릿 컨테이너 자동 등록으로 이중 실행되는 것을 막기 위해
 * SecurityConfig에서 직접 생성한다. 토큰 값은 로그에 남기지 않는다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenProvider jwtTokenProvider;

	public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
		this.jwtTokenProvider = jwtTokenProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
									FilterChain filterChain) throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			extractToken(request)
					.flatMap(jwtTokenProvider::parseToken)
					.ifPresent(claims -> {
						AuthenticatedUser principal = new AuthenticatedUser(claims.userId(), claims.role());
						SecurityContextHolder.getContext().setAuthentication(
								new UsernamePasswordAuthenticationToken(principal, null,
										List.of(new SimpleGrantedAuthority(claims.role()))));
					});
		}
		filterChain.doFilter(request, response);
	}

	/** Bearer 접두(대소문자 무시) 뒤의 토큰을 추출한다. 형식이 다르거나 비어 있으면 empty. */
	private java.util.Optional<String> extractToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return java.util.Optional.empty();
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(token);
	}
}
