package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.DataOrigin;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ContractStatusEventRepositoryIntegrationTest {

    @Autowired
    private ContractStatusEventRepository repository;

    @Autowired
    private ContractQueryRepository queryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("상태 사건은 효력일시·순번 순으로, 처리 결과는 Job별 행으로 조회한다")
    void selectsEventsAndJobProcessings() {
        Long contractId = jdbcTemplate.queryForObject("""
                SELECT contract_id
                  FROM fgc.contract_status_event
                 GROUP BY contract_id
                HAVING COUNT(*) >= 2
                 ORDER BY contract_id
                 LIMIT 1
                """, Long.class);

        var events = repository.findByContractIdOrderByEffectiveAtAscEventSeqAsc(contractId);
        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
        assertThat(events).isSortedAccordingTo(
                Comparator.comparing(ContractStatusEvent::getEffectiveAt)
                        .thenComparingInt(ContractStatusEvent::getEventSeq));

        Long eventId = events.getFirst().getContractStatusEventId();
        String jobName = "ContractStatusEventRepositoryTest-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fgc.contract_status_event_processing
                       (contract_status_event_id, processing_job, processing_status)
                VALUES (?, ?, 'SUCCEEDED')
                """, eventId, jobName);

        var processings = queryRepository.findStatusEventProcessingsByContractId(contractId);
        assertThat(processings)
                .anySatisfy(processing -> {
                    assertThat(processing.contractStatusEventId()).isEqualTo(eventId);
                    assertThat(processing.processingJob()).isEqualTo(jobName);
                    assertThat(processing.processingStatus()).isEqualTo("SUCCEEDED");
                    assertThat(processing.processedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("상태 사건을 저장하면 ID와 DB 생성시각이 채워지고 상태·출처·효력일시가 보존된다")
    void persistsStatusEventAndReadsGeneratedFields() {
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT MIN(contract_id) FROM fgc.insurance_contract", Long.class);
        Integer eventSeq = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(event_seq), 0) + 1
                  FROM fgc.contract_status_event WHERE contract_id = ?
                """, Integer.class, contractId);
        var effectiveAt = DateUtil.nowSeoul().minusDays(1);
        var receivedAt = DateUtil.nowSeoul();
        String sourceEventKey = "REPOSITORY-TEST:" + UUID.randomUUID();
        ContractStatusEvent event = ContractStatusEvent.builder()
                .contractId(contractId)
                .eventSeq(eventSeq)
                .previousStatus(ContractStatus.ACTIVE)
                .newStatus(ContractStatus.TERMINATED)
                .effectiveAt(effectiveAt)
                .receivedAt(receivedAt)
                .reasonCode("TEST_TERMINATED")
                .sourceSystem("FGC_MANUAL")
                .sourceEventKey(sourceEventKey)
                .dataOrigin(DataOrigin.MANUAL)
                .build();

        repository.saveAndFlush(event);
        assertThat(event.getContractStatusEventId()).isNotNull();
        entityManager.clear();

        ContractStatusEvent selected = repository.findById(event.getContractStatusEventId()).orElseThrow();
        assertThat(selected.getContractId()).isEqualTo(contractId);
        assertThat(selected.getEventSeq()).isEqualTo(eventSeq);
        assertThat(selected.getPreviousStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(selected.getNewStatus()).isEqualTo(ContractStatus.TERMINATED);
        // PostgreSQL timestamptz는 마이크로초 정밀도로 저장한다.
        assertThat(selected.getEffectiveAt().toInstant()).isCloseTo(effectiveAt.toInstant(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(selected.getReceivedAt().toInstant()).isCloseTo(receivedAt.toInstant(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(selected.getSourceEventKey()).isEqualTo(sourceEventKey);
        assertThat(selected.getDataOrigin()).isEqualTo(DataOrigin.MANUAL);
        assertThat(selected.getCreatedAt()).isNotNull();
    }
}
