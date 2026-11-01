package com.susukkang.fgc.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/** #637: 보관 경로는 웹 공개 디렉터리가 아닌 영속 볼륨으로 설정한다. */
@ConfigurationProperties("fgc.storage")
public record FileStorageProperties(@DefaultValue("./files") Path root,
                                    @DefaultValue("20971520") long maxFileBytes) {
    public FileStorageProperties {
        if (root == null || maxFileBytes <= 0 || maxFileBytes >= 50_000_000) {
            throw new IllegalArgumentException("저장소 경로와 50MB 미만의 업로드 상한이 필요합니다");
        }
    }
}
