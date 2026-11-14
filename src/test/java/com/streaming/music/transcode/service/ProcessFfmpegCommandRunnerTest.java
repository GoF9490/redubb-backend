package com.streaming.music.transcode.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.global.properties.AudioProperties;
import com.streaming.music.support.FakeProcessMain;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** JDK 하위 프로세스(FakeProcessMain)로 실행기 동작을 검증한다. 실제 FFmpeg는 사용하지 않는다. */
@Timeout(30)
class ProcessFfmpegCommandRunnerTest {

	private static final Duration LONG_TIMEOUT = Duration.ofSeconds(20);

	private final AudioProperties audioProperties = new AudioProperties("/tmp", "ffmpeg", 3, 6, "192k", 1L, 44100, 2, 500, 2, List.of(), List.of());
	private final ProcessFfmpegCommandRunner runner =
			new ProcessFfmpegCommandRunner(new FfmpegCommandBuilder(audioProperties), audioProperties);

	private List<String> createFakeCommand(String mode) throws Exception {
		Path classesDir = Path.of(FakeProcessMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		return List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classesDir.toString(),
				FakeProcessMain.class.getName(), mode);
	}

	@Test
	@DisplayName("정상 종료하면 SUCCESS와 종료 코드 0을 반환한다")
	void returnSuccessOnZeroExit() throws Exception {
		FfmpegResult result = runner.execute(createFakeCommand("ok"), LONG_TIMEOUT);

		assertThat(result.status()).isEqualTo(FfmpegResult.Status.SUCCESS);
		assertThat(result.isSuccess()).isTrue();
		assertThat(result.exitCode()).isZero();
		assertThat(result.logSummary()).contains("ok-marker");
	}

	@Test
	@DisplayName("비정상 종료하면 FAILED와 종료 코드, 마지막 로그를 반환한다")
	void returnFailedOnNonZeroExit() throws Exception {
		FfmpegResult result = runner.execute(createFakeCommand("fail"), LONG_TIMEOUT);

		assertThat(result.status()).isEqualTo(FfmpegResult.Status.FAILED);
		assertThat(result.exitCode()).isEqualTo(3);
		assertThat(result.logSummary()).contains("boom-error-line");
	}

	@Test
	@DisplayName("대용량 출력도 막히지 않고 완료하며 로그 요약은 500자 이하다")
	void consumeLargeOutputWithoutBlocking() throws Exception {
		FfmpegResult result = runner.execute(createFakeCommand("flood"), LONG_TIMEOUT);

		assertThat(result.status()).isEqualTo(FfmpegResult.Status.SUCCESS);
		assertThat(result.logSummary().length()).isLessThanOrEqualTo(500);
		assertThat(result.logSummary()).endsWith("END-MARKER");
	}

	@Test
	@DisplayName("타임아웃이 지나면 강제 종료하고 TIMEOUT을 반환한다")
	void returnTimeoutAndKillProcess() throws Exception {
		long start = System.nanoTime();

		FfmpegResult result = runner.execute(createFakeCommand("sleep"), Duration.ofSeconds(2));

		assertThat(result.status()).isEqualTo(FfmpegResult.Status.TIMEOUT);
		assertThat(result.exitCode()).isEqualTo(-1);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
	}

	@Test
	@DisplayName("타임아웃 시 자식 프로세스도 남지 않는다")
	void killChildProcessOnTimeout() throws Exception {
		Optional<ProcessHandle> child = Optional.empty();
		try {
			FfmpegResult result = runner.execute(createFakeCommand("spawn"), Duration.ofSeconds(5));

			assertThat(result.status()).isEqualTo(FfmpegResult.Status.TIMEOUT);
			Matcher matcher = Pattern.compile("CHILD_PID=(\\d+)").matcher(result.logSummary());
			assertThat(matcher.find()).as("자식 PID 로그").isTrue();
			child = ProcessHandle.of(Long.parseLong(matcher.group(1)));
			for (int i = 0; i < 50 && child.isPresent() && child.get().isAlive(); i++) {
				Thread.sleep(100);
			}
			assertThat(child.map(ProcessHandle::isAlive).orElse(false)).isFalse();
		} finally {
			child.ifPresent(ProcessHandle::destroyForcibly);
		}
	}

	@Test
	@DisplayName("존재하지 않는 실행 파일은 경로 없는 고정 요약의 FAILED를 반환한다")
	void returnFailedWhenExecutableMissing() {
		FfmpegResult result = runner.execute(List.of("/no/such/secret-path/ffmpeg-missing"), LONG_TIMEOUT);

		assertThat(result.status()).isEqualTo(FfmpegResult.Status.FAILED);
		assertThat(result.exitCode()).isEqualTo(-1);
		assertThat(result.logSummary()).isEqualTo("FFMPEG_START_FAILED").doesNotContain("secret-path");
	}

	@Test
	@DisplayName("인터럽트되면 프로세스를 종료하고 인터럽트 플래그를 복원한다")
	void restoreInterruptFlag() throws Exception {
		Thread.currentThread().interrupt();
		try {
			FfmpegResult result = runner.execute(createFakeCommand("sleep"), LONG_TIMEOUT);

			assertThat(result.status()).isEqualTo(FfmpegResult.Status.FAILED);
			assertThat(result.logSummary()).isEqualTo("INTERRUPTED");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	@DisplayName("FfmpegResult는 500자 초과 요약을 마지막 500자로 제한한다")
	void limitSummaryLength() {
		FfmpegResult result = FfmpegResult.failed(1, "a".repeat(900) + "TAIL");

		assertThat(result.logSummary()).hasSize(500).endsWith("TAIL");
	}
}
