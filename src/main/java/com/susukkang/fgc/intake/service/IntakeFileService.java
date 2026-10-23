package com.susukkang.fgc.intake.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.storage.FileStorage;
import com.susukkang.fgc.common.storage.UploadFileValidator;
import com.susukkang.fgc.intake.repository.IntakeFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** #640의 업로드/미리보기 경로가 재사용하는 내부 계약. 공개 파일 URL은 만들지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntakeFileService {
    private final FileStorage storage;
    private final UploadFileValidator validator;
    private final IntakeFileRepository repository;
    private final AuditLogService audit;
    private final PlatformTransactionManager transactionManager;

    @PreAuthorize("hasAnyRole('SETTLEMENT','GA_ADMIN','SYSTEM_ADMIN')")
    @Transactional
    public UploadedDocument upload(MultipartFile file, long uploadedBy) {
        var validated = validator.validate(file);
        repository.lockHash(validated.sha256());
        repository.findIdByHash(validated.sha256()).ifPresent(existing -> {
            throw new FgcBusinessException(FgcErrorCode.INTK_002, Map.of("documentId", existing));
        });
        try {
            String key = storage.store(validated.bytes());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            storage.delete(key);
                        } catch (IOException ex) {
                            log.error("업로드 롤백 파일 정리 실패 — 저장소 점검 필요");
                        }
                    }
                }
            });
            long id = repository.insert(validated.originalName(), key, validated.mediaType(), validated.bytes().length,
                    validated.sha256(), uploadedBy);
            audit.record(AuditLogService.AuditEvent.builder().actionCode("INTAKE_FILE_UPLOADED")
                    .entityType("intake_document").entityId(Long.toString(id)).userId(uploadedBy)
                    .after(Map.of("sha256", validated.sha256(), "fileSizeBytes", validated.bytes().length)).build());
            return new UploadedDocument(id, "UPLOADED", "UNCLASSIFIED");
        } catch (IOException ex) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }
    }

    @PreAuthorize("hasAnyRole('SETTLEMENT','GA_ADMIN','COMPLIANCE','SYSTEM_ADMIN')")
    @Transactional
    public <T> T withSource(long id, SourceReader<T> reader) {
        var source = repository.findSource(id).orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004));
        // 소비가 끝날 때까지 행의 공유 잠금을 유지한다. 파기 워커는 잠긴 행을 건너뛴다.
        try (var content = new SourceContent(source.mediaType(), storage.open(source.storageKey()))) {
            return reader.read(content);
        } catch (java.nio.file.NoSuchFileException ex) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004);
        } catch (IOException ex) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        }
    }

    /** #675가 호출할 삭제 경로. 자동 스케줄·보존일 계산은 여기서 임의로 만들지 않는다. */
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public PurgeResult purgeExpired(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("파기 묶음 크기는 1~1000입니다");
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var sources = tx.execute(status -> {
            var rows = repository.lockExpired(LocalDate.now(ZoneId.of("Asia/Seoul")), limit);
            for (var source : rows) {
                repository.markDeleteAttempt(source.documentId());
                if (!source.purged()) {
                    repository.markPurge(source.documentId());
                    audit.record(AuditLogService.AuditEvent.builder().actionCode("INTAKE_SOURCE_PURGED")
                            .entityType("intake_document").entityId(Long.toString(source.documentId()))
                            .after(Map.of("sourceAccess", "BLOCKED")).build());
                }
            }
            return rows;
        });
        // DB·감사 커밋이 성공하기 전에는 물리 원본을 삭제하지 않는다.
        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        for (var source : java.util.Objects.requireNonNull(sources)) {
            try {
                Boolean completed = tx.execute(status -> {
                    if (!repository.lockPendingDeletion(source.documentId())) return false;
                    try {
                        storage.delete(source.storageKey());
                        repository.confirmDeletion(source.documentId());
                        return true;
                    } catch (IOException ex) {
                        throw new FgcBusinessException(FgcErrorCode.COMMON_500);
                    }
                });
                if (Boolean.TRUE.equals(completed)) deleted++; else skipped++;
            } catch (RuntimeException ex) {
                // 한 파일의 영구 오류가 뒤 파일을 막지 않게 격리한다. 경로·내용은 로그에 넣지 않는다.
                failed++;
                log.error("원본 파기 실패 documentId={} errorType={}", source.documentId(), ex.getClass().getSimpleName());
            }
        }
        return new PurgeResult(sources.size(), deleted, skipped, failed);
    }

    public record UploadedDocument(long documentId, String status, String detectedType) { }
    public record PurgeResult(int selected, int deleted, int skipped, int failed) { }
    @FunctionalInterface
    public interface SourceReader<T> { T read(SourceContent source) throws IOException; }
    public record SourceContent(String mediaType, InputStream stream) implements AutoCloseable {
        @Override public void close() throws IOException { stream.close(); }
    }
}
