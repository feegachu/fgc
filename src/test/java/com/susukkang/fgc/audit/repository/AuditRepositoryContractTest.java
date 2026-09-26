package com.susukkang.fgc.audit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 공용 감사 Repository가 인터페이스 계약을 유지하며 불필요한 쓰기·삭제 API를 공개하지 않는지 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
class AuditRepositoryContractTest {

    /**
     * 설명 : 감사 저장 인터페이스가 저장 후 동기화 외의 일반 CRUD 메서드를 노출하지 않는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void writeRepositoryExposesOnlySaveAndFlush() {
        assertThat(AuditLogRepository.class.isInterface()).isTrue();
        assertThat(Repository.class.isAssignableFrom(AuditLogRepository.class)).isTrue();
        assertThat(CrudRepository.class.isAssignableFrom(AuditLogRepository.class)).isFalse();
        assertThat(AuditLogRepository.class.getMethods())
                .extracting(Method::getName)
                .containsExactly("saveAndFlush");
    }

    /**
     * 설명 : 감사 조회 인터페이스가 목록·건수·선택지 조회만 공개하고 저장·삭제 메서드는 상속하지 않는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void queryRepositoryExposesOnlyReadOperations() {
        assertThat(AuditLogQueryRepository.class.isInterface()).isTrue();
        assertThat(Repository.class.isAssignableFrom(AuditLogQueryRepository.class)).isTrue();
        assertThat(CrudRepository.class.isAssignableFrom(AuditLogQueryRepository.class)).isFalse();
        assertThat(AuditLogQueryRepository.class.getMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("selectAuditLogs", "countAuditLogs", "selectDistinctActionCodes",
                        "selectDistinctEntityTypes", "selectAuditUsers");
    }
}
