package com.streaming.music.support;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 테스트 전용 가짜 프로세스. FFmpeg 대신 JDK만으로 종료 코드, 대용량 출력, 장시간 대기를 흉내 낸다.
 * 모드: ok, fail, flood, sleep, spawn.
 */
public class FakeProcessMain {

	public static void main(String[] args) throws Exception {
		String mode = args[0];
		switch (mode) {
			case "ok" -> System.out.println("변환 완료 ok-marker");
			case "fail" -> {
				System.err.println("boom-error-line");
				System.exit(3);
			}
			case "flood" -> {
				String line = "x".repeat(99) + "\n";
				for (int i = 0; i < 10_000; i++) {
					System.err.print(line);
				}
				System.err.println("END-MARKER");
			}
			case "sleep" -> Thread.sleep(60_000);
			case "spawn" -> {
				Process child = new ProcessBuilder(
						Path.of(System.getProperty("java.home"), "bin", "java").toString(),
						"-cp", System.getProperty("java.class.path"),
						FakeProcessMain.class.getName(), "sleep")
						.inheritIO().start();
				System.out.println("CHILD_PID=" + child.pid());
				System.out.flush();
				Thread.sleep(60_000);
			}
			default -> throw new IOException("알 수 없는 모드: " + mode);
		}
	}
}
