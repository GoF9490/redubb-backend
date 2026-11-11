package com.streaming.music.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 기동 시 스토리지 버킷 존재를 확인하고 없으면 생성한다.
 * fail-fast: RustFS 미기동 시 예외가 전파되어 애플리케이션 기동이 실패한다.
 * local 프로파일은 RustFS 컨테이너가 필요하며, test 프로파일에서는 실행되지 않는다.
 */
@Component
@Profile("!test")
public class StorageBucketInitializer implements ApplicationRunner {

    private final StorageService storageService;

    public StorageBucketInitializer(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public void run(ApplicationArguments args) {
        storageService.ensureBucketExists();
    }
}
