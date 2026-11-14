package com.streaming.music.dto.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscodeEventDtoTest {

	@Test
	@DisplayName("필드는 trackId(UUID), originalS3Key(String) 2개뿐이다")
	void eventDtoHasOnlyTwoComponents() {
		RecordComponent[] components = TranscodeEventDto.class.getRecordComponents();

		assertThat(components).hasSize(2);
		assertThat(components[0].getName()).isEqualTo("trackId");
		assertThat(components[0].getType()).isEqualTo(UUID.class);
		assertThat(components[1].getName()).isEqualTo("originalS3Key");
		assertThat(components[1].getType()).isEqualTo(String.class);
	}

	@Test
	@DisplayName("trackId가 null이면 NPE가 발생한다")
	void eventDtoRejectsNullTrackId() {
		assertThatThrownBy(() -> new TranscodeEventDto(null, "key"))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("originalS3Key가 null이면 NPE가 발생한다")
	void eventDtoRejectsNullOriginalS3Key() {
		assertThatThrownBy(() -> new TranscodeEventDto(UUID.randomUUID(), null))
				.isInstanceOf(NullPointerException.class);
	}
}
