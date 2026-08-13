package com.susukkang.fgc.validation.service;

/**
 * 설명 : 월 통합검증 실행에서 선별된 계약의 예상 수수료 스케줄을 검증하고,
 * 스케줄 누락 또는 정책 변경 시 필요한 스케줄을 생성·재생성한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */

import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.mapper.ValidationScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ValidationRunScheduleService {
    private final ValidationScheduleMapper validationScheduleMapper;
    /**
     * 설명 : step 2에서 선별한 계약들의 스케줄 상태를 검사하며 없을시 생성 , 오래된 정책은 새 정책으로 변경한다.
     *
     * @param  validationRunId 검증 식별 ID
     * @return StepProcessingResult 배치 step 처리 결과
     * @author hjKang
     * @since 2026-08-13
     */
    @Transactional
    public StepProcessingResult validateContractSchedules(Long validationRunId) {
        // 2. 계약별 활성 OPERATIONAL 스케줄 상태 조회
        //    - 활성 헤더 수
        //    - scheduleHeaderId
        //    - 적용된 policyVersionId
        //    - 라인 수·중복 여부·총액
        validationScheduleMapper.selectSchedule
        // 3. 계약별 분기
        //    3-1. 활성 헤더 없음
        //         → 현행 정책으로 schedule_header/line 생성
        //
        //    3-2. 활성 헤더 중복
        //         → DATA_QUALITY 예외 생성 후 skip
        //
        //    3-3. 적용 정책 누락
        //         → POLICY_MISSING 예외 생성 후 skip
        //
        //    3-4. 적용 정책 중복
        //         → POLICY_DUPLICATE 예외 생성 후 skip
        //
        //    3-5. 기존 정책 버전이 현재 적용 버전과 다름
        //         → 기존 스케줄을 삭제하지 않음
        //         → 새 schedule_version으로 재생성
        //
        //    3-6. 회차·금액·업무키 정합성 오류
        //         → DATA_QUALITY 예외 생성 후 skip
        //         또는 기존 도메인 정책상 재계산 가능한 오류면 재생성
        //
        //    3-7. 모두 정상
        //         → 기존 스케줄 유지

        // 4. 처리 결과 반환
        //    → processed / regenerated / skipped / skip reason
        return null;
    }
}