package com.susukkang.fgc.validation.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #380 Phase 3 — ContractStatusEventProcessingRepository가 findPendingEventIds의
 * 상관 서브쿼리(NOT EXISTS ... SUCCEEDED)와 insertProcessing의 ON CONFLICT DO NOTHING
 * 멱등성을 올바르게 구현하는지 검증한다. 구 MyBatis ContractStatusEventProcessingMapper는
 * 이 전환 완료 후(#380 Phase 4) 다른 패키지에서 참조가 없어 삭제되었다 — 이 테스트가
 * 그 검증 책임을 이어받는다.
 *
 * contract_status_event / contract_status_event_processing은 append-only라 명시적
 * DELETE로 정리할 수 없다(reject_update_delete 트리거) — @Transactional로 테스트 종료 시
 * 자동 롤백시켜, 실제 계약에 테스트용 이벤트 이력이 영구히 남지 않도록 한다.
 */
@SpringBootTest
@Transactional
class ContractStatusEventProcessingRepositoryIntegrationTest {

    private static final String JOB_NAME = "ComparisonTestJob";

    @Autowired
    private ContractStatusEventProcessingRepository contractStatusEventProcessingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedContractId() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202607-0001");
    }

    private Long seedContractStatusEvent(Long contractId) {
        int eventSeq = 900000 + (int) (System.nanoTime() % 90000);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.contract_status_event
                    (contract_id, event_seq, new_status, effective_at, received_at,
                     source_system, source_event_key)
                VALUES (?, ?, 'LAPSED', now(), now(), 'TEST', ?)
                RETURNING contract_status_event_id
                """, Long.class, contractId, eventSeq, "repo-comparison-test-" + System.nanoTime());
    }

    @Test
    void findPendingEventIdsExcludesEventAfterItIsMarkedSucceeded() {
        Long contractId = seedContractId();
        Long eventId = seedContractStatusEvent(contractId);

        List<Long> before = contractStatusEventProcessingRepository.findPendingEventIds(contractId, JOB_NAME);
        assertThat(before).contains(eventId);

        contractStatusEventProcessingRepository.insertProcessing(eventId, JOB_NAME, "SUCCEEDED", null, null);

        List<Long> after = contractStatusEventProcessingRepository.findPendingEventIds(contractId, JOB_NAME);
        // after가 비어 있으면 doesNotContain은 공허하게 통과하므로(S5841), before에서 이 이벤트만 빠졌는지 비교한다
        List<Long> expected = before.stream().filter(id -> !id.equals(eventId)).toList();
        assertThat(after).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void insertProcessingSucceededTwiceForSameEventAndJobLeavesOnlyOneRow() {
        Long contractId = seedContractId();
        Long eventId = seedContractStatusEvent(contractId);

        contractStatusEventProcessingRepository.insertProcessing(eventId, JOB_NAME, "SUCCEEDED", null, null);
        contractStatusEventProcessingRepository.insertProcessing(eventId, JOB_NAME, "SUCCEEDED", null, null);

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.contract_status_event_processing
                 WHERE contract_status_event_id = ? AND processing_job = ? AND processing_status = 'SUCCEEDED'
                """, Integer.class, eventId, JOB_NAME);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void insertProcessingFailedTwiceForSameEventAndJobAccumulatesBothRows() {
        Long contractId = seedContractId();
        Long eventId = seedContractStatusEvent(contractId);

        contractStatusEventProcessingRepository.insertProcessing(eventId, JOB_NAME, "FAILED", null, "첫 번째 실패");
        contractStatusEventProcessingRepository.insertProcessing(eventId, JOB_NAME, "FAILED", null, "두 번째 실패(재시도)");

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.contract_status_event_processing
                 WHERE contract_status_event_id = ? AND processing_job = ? AND processing_status = 'FAILED'
                """, Integer.class, eventId, JOB_NAME);
        assertThat(count).isEqualTo(2);
    }
}
