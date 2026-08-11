package com.susukkang.fgc.validation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * fgc.contract_status_event / fgc.contract_status_event_processing 전용 매퍼
 */
@Mapper
public interface ContractStatusEventProcessingMapper {

    /**
     * contractId의 상태 이벤트 중, jobName 기준으로 아직 SUCCEEDED 처리이력이 없는 것만
     * effective_at ASC, event_seq ASC 순서로 돌려줌
     */
    List<Long> findPendingEventIds(@Param("contractId") Long contractId, @Param("jobName") String jobName);

    /**
     * 이 Job이 이벤트 하나를 처리한 결과(성공/실패)를 남김
     */
    void insertProcessing(@Param("contractStatusEventId") Long contractStatusEventId,
                           @Param("jobName") String jobName,
                           @Param("processingStatus") String processingStatus,
                           @Param("validationRunId") Long validationRunId,
                           @Param("failureReason") String failureReason);
}
