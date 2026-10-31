package com.susukkang.fgc.document.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.storage.FileStorage;
import com.susukkang.fgc.document.repository.GeneratedDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * IF-API-67A 생성 PDF의 판별 다운로드. 요청한 판의 원본을 읽어 발행 때 기록한 sha256과 대조한 뒤에만 내려준다.
 * 지금은 FUN-084 환수 통지서만 연다. 비교 확인서(COMPARE_CONFIRMATION)는 #657에서 권한 범위와 함께 연결한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedDocumentService {
    private static final Set<String> DOWNLOADABLE_KINDS = Set.of("CLAWBACK_NOTICE");

    private final GeneratedDocumentRepository documents;
    private final FileStorage storage;

    /** 배열 필드라 record 기본 equals·hashCode는 참조만 비교한다. 내용 기준으로 바꾸고 toString에는 원본 대신 크기만 남긴다. */
    public record DocumentFile(String documentNo, int version, byte[] content) {
        @Override
        public boolean equals(Object o) {
            return o instanceof DocumentFile other && version == other.version
                    && documentNo.equals(other.documentNo) && Arrays.equals(content, other.content);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(documentNo, version) + Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "DocumentFile[documentNo=" + documentNo + ", version=" + version + ", bytes=" + content.length + "]";
        }
    }

    @Transactional(readOnly = true)
    public DocumentFile download(String documentNo, int version) {
        if (documentNo == null || !documentNo.matches("[A-Za-z0-9-]{1,60}")) throw invalid("documentNo");
        if (version < 1) throw invalid("version");
        var document = documents.findVersion(documentNo, version)
                .filter(d -> DOWNLOADABLE_KINDS.contains(d.documentKind()))
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004,
                        Map.of("documentNo", documentNo, "version", version)));
        byte[] content;
        try (var in = storage.open(document.storageKey())) {
            content = in.readAllBytes();
        } catch (IOException e) {
            log.error("생성 문서 원본을 읽을 수 없음 documentId={}", document.documentId(), e);
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }
        if (!sha256(content).equals(document.contentHash())) {
            // 볼륨 복원·수동 정리로 원본이 바뀌었다. 발행 증빙과 다른 파일은 내려주지 않는다.
            log.error("생성 문서 해시 불일치 documentId={}", document.documentId());
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }
        return new DocumentFile(document.documentNo(), document.versionNo(), content);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
