package com.susukkang.fgc.schedule.mapper;

import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.schedule.dto.*;
import com.susukkang.fgc.common.code.PaymentStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 예상 스케줄 헤더와 회차별 라인의 조회 및 저장을 담당하는 MyBatis Mapper.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-07
 */
@Mapper
public interface ScheduleMapper {
    /**
     * 검색 조건에 해당하는 예상 스케줄 헤더 목록을 조회한다.
     * @param condition,size,offset 스케줄 검색 조건,최대 스케줄 수,검색 시작 번호
     * @return 예상 스케줄 헤더 목록
     */
    List<ScheduleHeaderResponse> selectByCondition(
            @Param("condition") ScheduleSearchCondition condition,
            @Param("size") int size,
            @Param("offset") long offset
    );
    /**
     * 현재 검색조건에 맞는 스케줄의 수를 구한다 -> 최대 페이지 수 계산
     * @param condition 스케줄 검색 조건
     * @return 예상 스케줄 헤더 수
     */
    long countByCondition(@Param("condition") ScheduleSearchCondition condition);
    /**
     * 예상 스케줄 헤더 한 건을 저장한다.
     * @param header 저장할 스케줄 헤더
     * @return 저장된 행 수
     */
    int insertScheduleHeader(ScheduleHeaderInsertDTO header);
    /**
     * 설명 : 스케줄 헤더 Id를 통해 스케줄 헤더와 회차별 라인을 조회한다.
     *
     * @param scheduleHeaderId 스케줄 헤더 ID
     * @return 스케줄 헤더 1건 및 스케줄 라인 N건
     * @author hjKang
     * @since 2026-08-09
     */
    ScheduleDetailResponse selectScheduleDetailById(
            @Param("scheduleHeaderId") Long scheduleHeaderId
    );

    List<ScheduleHeaderResponse> selectVersionsByScheduleHeaderId(
            @Param("scheduleHeaderId") Long scheduleHeaderId
    );

    /**
     * 설명 : 스케줄 라인 목록을 schedule_line에 일괄 저장한다.
     * @param scheduleLineList 스케줄 라인 목록
     * @return 삽입된 스케줄 라인의 수
     * @author hjKang
     * @since 2026-08-09
     */
    int insertAllScheduleLines(@Param("lines") List<ScheduleLineInsertDTO> scheduleLineList);

    /**
     * 정책 미존재·중복으로 스케줄을 생성하지 못한 지급단계를 검토 예외 큐에 등록한다.
     * 동일 계약·지급단계·예외유형은 한 건으로 유지한다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @param exceptionType POLICY_MISSING 또는 POLICY_DUPLICATE
     * @param title 검토 건 제목
     * @param description 검토 사유
     * @return 삽입 또는 갱신된 행 수
     */
    int upsertPolicyReviewCase(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage,
            @Param("exceptionType") String exceptionType,
            @Param("title") String title,
            @Param("description") String description
    );

    /** 계약 단위 스케줄 생성을 직렬화하기 위해 계약 행을 잠근다. */
    Long lockContractForScheduleGeneration(@Param("contractId") Long contractId);

    /** 현재 활성 운영 스케줄에 적용된 정책 버전 ID를 조회한다. */
    Long selectActiveOperationalPolicyVersionId(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );

    /** 계약·지급단계의 다음 스케줄 버전 번호를 조회한다. */
    int selectNextScheduleVersionNo(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );

    /** 기존 활성 운영 스케줄을 비활성화한다. */
    int deactivateActiveOperationalSchedule(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );

    /** 계약 수정 시 새 버전으로 교체할 활성 운영 스케줄 ID를 조회한다. */
    List<Long> selectActiveOperationalScheduleIds(@Param("contractId") Long contractId);

    ScheduleDetailResponse selectByContractIdAndPaymentStage(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );
    // 스케줄 ID를 통해 헤더 조회
    ScheduleHeaderInsertDTO selectScheduleHeaderById(@Param("scheduleId") Long scheduleId);

    /** 예정 상태인 회차를 확정 상태로 변경한다. */
    int confirmPlannedScheduleLines(@Param("scheduleHeaderId") Long scheduleHeaderId);

    /** 활성 예정 스케줄 헤더를 확정한다. */
    int confirmScheduleHeader(@Param("scheduleHeaderId") Long scheduleHeaderId);

    // 스케줄 ID를 통해 기존 회차별 라인을 조회
    List<ScheduleLineInsertDTO> selectScheduleLinesByScheduleId(@Param("scheduleId") Long scheduleId);

    // 스케줄 헤더 상태 및 활성화 여부 업데이트
    int updateScheduleHeaderStatus(
            @Param("scheduleHeaderId") Long scheduleHeaderId,
            @Param("scheduleHeaderStatus") ScheduleHeaderStatus scheduleHeaderStatus,
            @Param("active") boolean active
    );
}
