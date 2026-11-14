package com.streaming.music.playback.service;

import com.streaming.music.playback.entity.ActionType;
import com.streaming.music.playback.entity.PlaybackLog;
import com.streaming.music.track.entity.Track;
import com.streaming.music.user.entity.User;
import com.streaming.music.playback.repository.PlaybackLogRepository;
import com.streaming.music.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재생(STREAM)·다운로드(DOWNLOAD) 이력 기록 서비스 (PRD-05 §7.1.3).
 *
 * <p>이력 저장 실패가 재생·다운로드 응답을 막지 않도록 {@link RuntimeException}은 로그만 남기고 삼킨다.
 *
 * <p>트랜잭션 설계:
 * <ul>
 *   <li>저장은 {@code TransactionTemplate}(REQUIRES_NEW)으로 호출자 트랜잭션과 분리해 실행한다.
 *       호출자가 readOnly 트랜잭션이면 합류 시 INSERT가 flush되지 않고, 저장 실패를 삼켜도
 *       호출자 트랜잭션이 rollback-only로 마킹되어 커밋 시 UnexpectedRollbackException이 나기 때문이다.</li>
 *   <li>어노테이션 대신 TransactionTemplate을 쓰므로 self-invocation 프록시 문제가 없고,
 *       커밋 시점 예외도 try/catch 안에서 잡힌다.</li>
 * </ul>
 *
 * <p>MVP 한계:
 * <ul>
 *   <li>REQUIRES_NEW는 호출자가 커넥션을 쥔 상태에서 두 번째 커넥션을 사용한다. 커넥션 풀이 작으면 고갈 위험이 있다.</li>
 *   <li>존재하지 않는 userId이면 FK 위반으로 이력이 유실된다(예외는 삼키므로 응답에는 영향 없음).</li>
 *   <li>Mockito 단위 테스트로는 REQUIRES_NEW 동작, 호출자 readOnly 트랜잭션에서의 flush,
 *       rollback-only 비전파를 검증할 수 없다. 해당 동작은 통합 테스트(H2)에서 확인해야 한다.</li>
 * </ul>
 */
@Slf4j
@Service
public class PlaybackLogService {

	private final PlaybackLogRepository playbackLogRepository;
	private final UserRepository userRepository;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public PlaybackLogService(PlaybackLogRepository playbackLogRepository,
			UserRepository userRepository,
			Clock clock,
			PlatformTransactionManager transactionManager) {
		this.playbackLogRepository = playbackLogRepository;
		this.userRepository = userRepository;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 이력 1건을 저장한다. 저장 중 RuntimeException은 전파하지 않는다.
	 *
	 * @param track      대상 트랙 (필수)
	 * @param userId     로그인 사용자 ID, 비로그인이면 null
	 * @param actionType STREAM 또는 DOWNLOAD (필수)
	 */
	public void recordLog(Track track, Long userId, ActionType actionType) {
		// 프로그래밍 오류는 삼키지 않고 즉시 드러낸다
		Objects.requireNonNull(track, "track");
		Objects.requireNonNull(actionType, "actionType");

		try {
			transactionTemplate.executeWithoutResult(status -> {
				User user = (userId != null) ? userRepository.getReferenceById(userId) : null;
				playbackLogRepository.save(PlaybackLog.builder()
						.track(track)
						.user(user)
						.actionType(actionType)
						.createdAt(Instant.now(clock))
						.build());
			});
		} catch (RuntimeException e) {
			log.warn("재생 이력 저장 실패 trackId={}, userId={}, actionType={}",
					track.getTrackId(), userId, actionType, e);
		}
	}
}
