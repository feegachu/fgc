package com.susukkang.fgc.common.storage;

import java.io.IOException;
import java.io.InputStream;

/** 저장키만 업무 DB에 보관한다. HTTP 정적 경로 또는 절대 파일 경로를 반환하지 않는다. */
public interface FileStorage {
    String store(byte[] content) throws IOException;
    InputStream open(String key) throws IOException;
    boolean delete(String key) throws IOException;
}
