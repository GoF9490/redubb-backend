package com.streaming.music.transcode.service;

import com.streaming.music.global.properties.AudioProperties;
import com.streaming.music.track.repository.TrackRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동 시 오래 방치된 PENDING/PROCESSING 트랙을 FAILED("INTERRUPTED_BY_RESTART")로 정리한다(PRD-05 §6.3-5).
 * <p>
 * 대상은 {@code updatedAt}이 {@code ffmpeg-timeout-minutes × 2} 이상 경과한 트랙뿐이다.
 * 자동 재변환은 MVP 범위에서 제외한다.
 * <ul>
 *   <li>다중 노드: web1/web2가 동시에 기동해도 같은 조건의 UPDATE가 중복 실행될 뿐이라 멱등하다.</li>
 *   <li>보호: 정상 변환 중인 PROCESSING 트랙은 시작 시점에 {@code updatedAt}이 갱신되고 변환은 최대
 *       timeout(기본 3분)이므로, 기본값 기준 6분 이내에는 다른 노드 재시작에도 FAILED가 되지 않는다.</li>
 *   <li>MVP 한계: 변환 큐는 용량 50, core 1이라 부하 시 큐 뒤쪽 PENDING은 수십 분 대기할 수 있다.
 *       큐 대기 시간이 timeout×2(기본 6분)를 넘는 PENDING은 다른 노드 재시작 시 FAILED가 될 수 있다
 *       (이후 워커가 꺼내도 PENDING 가드가 FAILED 트랙을 건너뛴다). PRD 정책(updatedAt 기준)을 따른 한계이며
 *       개선(PENDING 임계 분리 등)은 하지 않는다.</li>
 *   <li>실패 정책: 복구는 부가 정리이므로 예외 시 error 로그만 남기고 기동을 계속한다(다음 기동 때 재정리된다).</li>
 *   <li>test 프로파일에서는 실행하지 않아 통합 테스트 데이터에 간섭하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("!test")
public class TranscodeRecoveryRunner implements ApplicationRunner {

	public static final String INTERRUPTED_MESSAGE = "INTERRUPTED_BY_RESTART";

	private final TrackRepository trackRepository;
	private final AudioProperties audioProperties;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public TranscodeRecoveryRunner(TrackRepository trackRepository,
			AudioProperties audioProperties,
			Clock clock,
			PlatformTransactionManager transactionManager) {
		this.trackRepository = trackRepository;
		this.audioProperties = audioProperties;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@Override
	public void run(ApplicationArguments args) {
		// 같은 시각 값을 threshold 계산과 updatedAt 지정에 함께 사용한다
		Instant now = Instant.now(clock);
		long staleMinutes = audioProperties.ffmpegTimeoutMinutes() * (long) audioProperties.recoveryStaleThresholdMultiplier();
		Instant threshold = now.minus(Duration.ofMinutes(staleMinutes));
		try {
			Integer updated = transactionTemplate.execute(
					status -> trackRepository.failStaleTracks(threshold, now, INTERRUPTED_MESSAGE));
			log.info("기동 시 미완료 트랙 정리 완료: {}건 FAILED 처리", updated);
		} catch (Exception e) {
			log.error("기동 시 미완료 트랙 정리에 실패했습니다. 기동은 계속합니다.", e);
		}
	}
}
