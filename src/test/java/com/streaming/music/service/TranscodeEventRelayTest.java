package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.music.dto.event.TranscodeEventDto;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;

class TranscodeEventRelayTest {

    private TranscodeProcessor processor;
    private TranscodeRejectionHandler rejectionHandler;
    private ObjectProvider<TranscodeProcessor> provider;
    private TranscodeEventRelay relay;
    private TranscodeEventDto dto;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        processor = mock(TranscodeProcessor.class);
        rejectionHandler = mock(TranscodeRejectionHandler.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(processor);
        relay = new TranscodeEventRelay(provider, rejectionHandler);
        dto = new TranscodeEventDto(UUID.randomUUID(), "original/a.mp3");
    }

    @Test
    @DisplayName("정상 제출이면 processor만 호출하고 거부 처리는 하지 않는다")
    void submitsToProcessorWithoutRejectionHandling() {
        relay.relayTranscode(dto);

        verify(processor).process(dto);
        verify(rejectionHandler, never()).markQueueFull(any());
    }

    @Test
    @DisplayName("큐 포화로 거부되면 markQueueFull을 호출하고 예외를 전파하지 않는다")
    void marksQueueFullWhenRejected() {
        doThrow(new TaskRejectedException("full")).when(processor).process(dto);

        assertThatCode(() -> relay.relayTranscode(dto)).doesNotThrowAnyException();
        verify(rejectionHandler).markQueueFull(dto.trackId());
    }

    @Test
    @DisplayName("일반 RuntimeException은 삼키고 큐 포화 처리는 하지 않는다")
    void swallowsOtherRuntimeException() {
        doThrow(new IllegalStateException("boom")).when(processor).process(dto);

        assertThatCode(() -> relay.relayTranscode(dto)).doesNotThrowAnyException();
        verify(rejectionHandler, never()).markQueueFull(any());
    }

    @Test
    @DisplayName("processor 빈이 없어도 예외 없이 종료한다")
    void doesNothingWhenProcessorAbsent() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> relay.relayTranscode(dto)).doesNotThrowAnyException();
        verify(rejectionHandler, never()).markQueueFull(any());
    }
}
