package com.streaming.music.service;

/**
 * 프로세스 출력의 마지막 N문자만 보관한다(전체 출력을 메모리에 쌓지 않음). 스레드 안전.
 */
class LogTailBuffer {

    private final int maxLength;
    private final StringBuilder buffer = new StringBuilder();

    LogTailBuffer(int maxLength) {
        this.maxLength = maxLength;
    }

    synchronized void append(char[] chars, int offset, int length) {
        buffer.append(chars, offset, length);
        int excess = buffer.length() - maxLength;
        if (excess > 0) {
            buffer.delete(0, excess);
        }
    }

    /** 보관된 마지막 로그. 길이는 항상 maxLength 이하이며 앞뒤 공백은 제거한다. */
    @Override
    public synchronized String toString() {
        return buffer.toString().strip();
    }
}
