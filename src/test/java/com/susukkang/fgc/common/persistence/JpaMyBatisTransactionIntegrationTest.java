package com.susukkang.fgc.common.persistence;

import com.susukkang.fgc.base.repository.InsurerRepository;
import com.susukkang.fgc.common.persistence.support.MixedPersistenceTestSupport;
import com.susukkang.fgc.common.persistence.support.MixedPersistenceTestSupport.MixedInsurerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.susukkang.fgc.common.persistence.support.MixedPersistenceTestSupport.newInsurer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설명 : 실제 JPA·MyBatis 저장의 공동 커밋/롤백과 REQUIRES_NEW 경계를 검증한다.
 * 테스트 전체를 @Transactional로 감싸지 않고, 종료된 트랜잭션의 DB 결과를 확인한다.
 *
 * @author Codex
 * @version 1.0
 * @since 2026-09-24
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
@ActiveProfiles("test")
@Import(MixedPersistenceTestSupport.class)
class JpaMyBatisTransactionIntegrationTest {

    @Autowired
    private InsurerRepository insurerRepository;

    @Autowired
    private MixedInsurerMapper mixedInsurerMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final String prefix = "TX-" + UUID.randomUUID().toString().substring(0, 12);
    private TransactionTemplate required;
    private TransactionTemplate requiresNew;

    @BeforeEach
    void setUpTransactions() {
        required = new TransactionTemplate(transactionManager);
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void cleanUpOwnRows() {
        jdbcTemplate.update("DELETE FROM fgc.insurer WHERE insurer_code LIKE ?", prefix + "%");
    }

    @Test
    void commitsJpaAndMyBatisWritesTogether() {
        required.executeWithoutResult(status -> writePair("C"));

        assertPersistedCodes(code("C-J"), code("C-M"));
    }

    @Test
    void applicationFailureRollsBackBothJpaAndMyBatisWrites() {
        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            writePair("R");
            throw new ExpectedRollback("outer failure");
        })).isInstanceOf(ExpectedRollback.class);

        assertPersistedCodes();
    }

    @Test
    void myBatisConstraintFailureRollsBackPreviouslyFlushedJpaWrite() {
        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            writePair("M");
            // 이미 JPA가 INSERT한 코드와 충돌시켜 MyBatis SQL에서 실제 DB 오류를 낸다.
            mixedInsurerMapper.insert(code("M-J"), "중복 코드");
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertPersistedCodes();
    }

    @Test
    void jpaConstraintFailureRollsBackPreviousMyBatisWrite() {
        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            assertThat(mixedInsurerMapper.insert(code("J-M"), "MyBatis 선행 저장")).isEqualTo(1);
            insurerRepository.saveAndFlush(newInsurer(code("J-J")));
            // 이번에는 JPA SQL에서 실패해도 먼저 실행한 MyBatis INSERT가 남지 않아야 한다.
            insurerRepository.saveAndFlush(newInsurer(code("J-M")));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertPersistedCodes();
    }

    @Test
    void requiresNewCommitSurvivesOuterRollback() {
        assertThatThrownBy(() -> required.executeWithoutResult(outer -> {
            writePair("O");
            requiresNew.executeWithoutResult(inner -> writePair("I"));
            // 내부 커밋 후 원래 트랜잭션으로 돌아왔는지도 함께 검증한다.
            writePair("A");
            throw new ExpectedRollback("outer failure");
        })).isInstanceOf(ExpectedRollback.class);

        assertPersistedCodes(code("I-J"), code("I-M"));
    }

    @Test
    void requiresNewRollbackDoesNotRollBackOuterWrites() {
        required.executeWithoutResult(outer -> {
            writePair("O");
            assertThatThrownBy(() -> requiresNew.executeWithoutResult(inner -> {
                writePair("I");
                throw new ExpectedRollback("inner failure");
            })).isInstanceOf(ExpectedRollback.class);
            writePair("A");
        });

        assertPersistedCodes(code("O-J"), code("O-M"), code("A-J"), code("A-M"));
    }

    private void writePair(String phase) {
        // flush까지 실행해 영속성 컨텍스트에만 남은 변경으로 롤백 테스트가 통과하지 않게 한다.
        insurerRepository.saveAndFlush(newInsurer(code(phase + "-J")));
        assertThat(mixedInsurerMapper.insert(code(phase + "-M"), "MyBatis 혼합 저장")).isEqualTo(1);
    }

    private void assertPersistedCodes(String... expectedCodes) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // 트랜잭션 종료 후 JDBC로 읽어 JPA/MyBatis 캐시가 아닌 실제 커밋 결과를 확인한다.
        assertThat(jdbcTemplate.queryForList(
                "SELECT insurer_code FROM fgc.insurer WHERE insurer_code LIKE ?",
                String.class, prefix + "%"))
                .containsExactlyInAnyOrder(expectedCodes);
    }

    private String code(String suffix) {
        return prefix + "-" + suffix;
    }

    private static class ExpectedRollback extends RuntimeException {
        ExpectedRollback(String message) {
            super(message);
        }
    }
}
