package com.susukkang.fgc.contract.mapper;

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
class ContractStatusEventMapperIntegrationTest {

    @Autowired
    private ContractStatusEventMapper mapper;

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

        var events = mapper.selectByContractId(contractId);
        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
        assertThat(events).isSortedAccordingTo(
                Comparator.comparing(com.susukkang.fgc.contract.dto.ContractStatusEventRow::effectiveAt)
                        .thenComparingInt(com.susukkang.fgc.contract.dto.ContractStatusEventRow::eventSeq));

        Long eventId = events.getFirst().contractStatusEventId();
        String jobName = "ContractStatusEventMapperTest-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fgc.contract_status_event_processing
                       (contract_status_event_id, processing_job, processing_status)
                VALUES (?, ?, 'SUCCEEDED')
                """, eventId, jobName);

        var processings = mapper.selectProcessingsByContractId(contractId);
        assertThat(processings)
                .anySatisfy(processing -> {
                    assertThat(processing.contractStatusEventId()).isEqualTo(eventId);
                    assertThat(processing.processingJob()).isEqualTo(jobName);
                    assertThat(processing.processingStatus()).isEqualTo("SUCCEEDED");
                    assertThat(processing.processedAt()).isNotNull();
                });
    }
}
