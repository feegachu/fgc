package com.susukkang.fgc.intake.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.storage.FileStorage;
import com.susukkang.fgc.common.storage.PrivateVolumeFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** 별도 일회성 PostgreSQL + 전용 임시 볼륨. 개발·운영 DB와 사용자 파일을 사용하지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_files_637?currentSchema=fgc")
@DirtiesContext
@WithMockUser(roles = "SYSTEM_ADMIN")
class IntakeFileServiceIntegrationTest {
    private static final Path ROOT = Path.of("build", "tmp", "intake-637-" + UUID.randomUUID()).toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("fgc.storage.root", ROOT::toString);
    }
    @Autowired IntakeFileService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestStorage storage;
    long userId;

    @BeforeEach void setup() {
        storage.failDelete.set(false);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_637_fail_audit ON fgc.audit_log");
        jdbc.update("DELETE FROM fgc.intake_document");
        userId = jdbc.queryForObject("SELECT min(user_id) FROM fgc.app_user", Long.class);
    }

    @Test void persistsMetadataAndReadsExactOriginal() throws Exception {
        var result = service.upload(csv("metadata"), userId);
        assertThat(result.status()).isEqualTo("UPLOADED");
        var row = jdbc.queryForMap("SELECT * FROM fgc.intake_document WHERE intake_document_id = ?", result.documentId());
        assertThat(row.get("storage_key").toString()).matches("[a-f0-9]{32}");
        assertThat(row.get("content_hash").toString()).hasSize(64);
        byte[] actual = service.withSource(result.documentId(), source -> source.stream().readAllBytes());
        assertThat(actual).isEqualTo(csv("metadata").getBytes());
        assertThat(audits(result.documentId(), "INTAKE_FILE_UPLOADED")).isEqualTo(1);
    }

    @Test void duplicateReturnsExistingDocumentWithoutAdditionalFile() throws Exception {
        var first = service.upload(csv("duplicate"), userId);
        long count = fileCount();
        assertThatThrownBy(() -> service.upload(csv("duplicate"), userId))
                .isInstanceOfSatisfying(FgcBusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(FgcErrorCode.INTK_002);
                    assertThat(ex.getParams()).containsEntry("documentId", first.documentId());
                });
        assertThat(fileCount()).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.intake_document", Long.class)).isEqualTo(1);
    }

    @Test void outerRollbackRemovesMetadataAuditAndPhysicalFile() throws Exception {
        long before = fileCount();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.upload(csv("rollback"), userId);
            tx.setRollbackOnly();
        });
        assertThat(fileCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.intake_document", Long.class)).isZero();
    }

    @Test void auditFailureRollsBackDocumentAndCleansFile() throws Exception {
        long before = fileCount();
        failAudit();
        assertThatThrownBy(() -> service.upload(csv("audit failure"), userId)).isInstanceOf(RuntimeException.class);
        assertThat(fileCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.intake_document", Long.class)).isZero();
    }

    @Test void concurrentIdenticalUploadsHaveOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                authenticate();
                try {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try { service.upload(csv("concurrent"), userId); return "created"; }
                    catch (FgcBusinessException ex) { return ex.getErrorCode().getCode(); }
                } finally { SecurityContextHolder.clearContext(); }
            })).toList();
            start.countDown();
            assertThat(List.of(tasks.get(0).get(30, TimeUnit.SECONDS), tasks.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "FGC-INTK-002");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.intake_document", Long.class)).isEqualTo(1);
    }

    @Test void expiredSourceIsDeletedButMetadataAndAuditArePreserved() {
        long id = service.upload(csv("expired"), userId).documentId();
        expire(id);
        assertThat(service.purgeExpired(100)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_deleted_at IS NOT NULL FROM fgc.intake_document WHERE intake_document_id=?", Boolean.class, id)).isTrue();
        assertThat(audits(id, "INTAKE_SOURCE_PURGED")).isEqualTo(1);
        assertThat(service.purgeExpired(100)).isZero();
        assertThatThrownBy(() -> service.withSource(id, source -> 1))
                .isInstanceOfSatisfying(FgcBusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test void deletionFailureRemainsBlockedAndResumesWithoutDuplicateAudit() {
        long id = service.upload(csv("retry purge"), userId).documentId();
        expire(id);
        storage.failDelete.set(true);
        assertThatThrownBy(() -> service.purgeExpired(100)).isInstanceOf(FgcBusinessException.class);
        assertThat(jdbc.queryForObject("SELECT source_purged_at IS NOT NULL AND source_deleted_at IS NULL FROM fgc.intake_document WHERE intake_document_id=?", Boolean.class, id)).isTrue();
        assertThatThrownBy(() -> service.withSource(id, source -> 1)).isInstanceOf(FgcBusinessException.class);
        storage.failDelete.set(false);
        assertThat(service.purgeExpired(100)).isEqualTo(1);
        assertThat(audits(id, "INTAKE_SOURCE_PURGED")).isEqualTo(1);
    }

    @Test void purgeAuditFailureDoesNotDeleteOriginal() throws Exception {
        long id = service.upload(csv("purge rollback"), userId).documentId();
        expire(id);
        long before = fileCount();
        failAudit();
        assertThatThrownBy(() -> service.purgeExpired(100)).isInstanceOf(RuntimeException.class);
        assertThat(fileCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT source_purged_at IS NULL FROM fgc.intake_document WHERE intake_document_id=?", Boolean.class, id)).isTrue();
        byte[] preserved = service.withSource(id, source -> source.stream().readAllBytes());
        assertThat(preserved).isEqualTo(csv("purge rollback").getBytes());
    }

    @Test void unexpiredOrExtractingSourcesAreNotPurged() {
        long id = service.upload(csv("keep"), userId).documentId();
        jdbc.update("UPDATE fgc.intake_document SET purge_after = CURRENT_DATE + 1 WHERE intake_document_id=?", id);
        assertThat(service.purgeExpired(100)).isZero();
        expire(id);
        jdbc.update("UPDATE fgc.intake_document SET status='EXTRACTING' WHERE intake_document_id=?", id);
        assertThat(service.purgeExpired(100)).isZero();
    }

    @Test void purgeBatchesAdvancePastAlreadyDeletedMetadata() {
        for (int i = 0; i < 3; i++) expire(service.upload(csv("batch " + i), userId).documentId());
        for (int i = 0; i < 3; i++) assertThat(service.purgeExpired(1)).isEqualTo(1);
        assertThat(service.purgeExpired(1)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.intake_document WHERE source_deleted_at IS NOT NULL", Long.class)).isEqualTo(3);
    }

    @Test void realXlsxStoresItsHashAndExactOriginal() throws Exception {
        byte[] bytes;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            workbook.createSheet("합성").createRow(0).createCell(0).setCellValue("정답");
            workbook.write(out);
            bytes = out.toByteArray();
        }
        var upload = new MockMultipartFile("file", "명세.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        long id = service.upload(upload, userId).documentId();
        byte[] actual = service.withSource(id, source -> source.stream().readAllBytes());
        assertThat(actual).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("SELECT file_size_bytes FROM fgc.intake_document WHERE intake_document_id=?", Long.class, id)).isEqualTo(bytes.length);
    }

    @Test void activeReaderPreventsPurgeUntilConsumptionCompletes() throws Exception {
        long id = service.upload(csv("leased"), userId).documentId();
        expire(id);
        var opened = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var read = pool.submit(() -> {
                authenticate();
                try { return service.withSource(id, source -> {
                    opened.countDown();
                    try { if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("테스트 시간 초과"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    return source.stream().readAllBytes();
                }); } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(opened.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(service.purgeExpired(100)).isZero();
            } finally { release.countDown(); }
            assertThat(read.get(30, TimeUnit.SECONDS)).isEqualTo(csv("leased").getBytes());
        }
        assertThat(service.purgeExpired(100)).isEqualTo(1);
    }

    @Test @WithMockUser(roles = "AGENT") void agentCannotUploadOrReadOriginal() {
        assertThatThrownBy(() -> service.upload(csv("forbidden"), userId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.withSource(1, source -> 1)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.purgeExpired(1)).isInstanceOf(AccessDeniedException.class);
    }

    @Test @WithMockUser(roles = "COMPLIANCE") void complianceCannotUpload() {
        assertThatThrownBy(() -> service.upload(csv("forbidden"), userId)).isInstanceOf(AccessDeniedException.class);
    }

    private long audits(long id, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log WHERE entity_type='intake_document' AND entity_id=? AND action_code=?", Long.class, Long.toString(id), action);
    }
    private void expire(long id) { jdbc.update("UPDATE fgc.intake_document SET purge_after=CURRENT_DATE WHERE intake_document_id=?", id); }
    private long fileCount() throws IOException {
        if (!Files.exists(ROOT)) return 0;
        try (var stream = Files.list(ROOT)) { return stream.count(); }
    }
    private void failAudit() {
        jdbc.execute("CREATE OR REPLACE FUNCTION fgc.fail_file_audit_637() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '637 audit fault injection'; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER trg_637_fail_audit BEFORE INSERT ON fgc.audit_log FOR EACH ROW EXECUTE FUNCTION fgc.fail_file_audit_637()");
    }
    private static MockMultipartFile csv(String key) {
        return new MockMultipartFile("file", "합성.csv", "text/csv", ("id,value\n" + key + ",1000").getBytes(StandardCharsets.UTF_8));
    }
    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
    }

    @TestConfiguration static class StorageConfig {
        @Bean @Primary TestStorage testStorage(PrivateVolumeFileStorage actual) { return new TestStorage(actual); }
    }
    static final class TestStorage implements FileStorage {
        final AtomicBoolean failDelete = new AtomicBoolean();
        private final FileStorage delegate;
        TestStorage(FileStorage delegate) { this.delegate = delegate; }
        @Override public String store(byte[] content) throws IOException { return delegate.store(content); }
        @Override public InputStream open(String key) throws IOException { return delegate.open(key); }
        @Override public boolean delete(String key) throws IOException {
            if (failDelete.get()) throw new IOException("test fault injection");
            return delegate.delete(key);
        }
    }
}
