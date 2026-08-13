package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.mapper.ValidationTargetSelectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 설명 : 배치 대상을 선별하기 위한 구현체
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */
@Service
@RequiredArgsConstructor
public class ValidationTargetSelectionService {

    private final ValidationTargetSelectionMapper validationTargetSelectionMapper;

    /**
     * 설명 : 월 통합검증 실행의 검증월과 실행 유형을 기준으로 검증 대상 계약 및 관련 데이터를 선별한다.
     *
     * @param validationRunId 월 통합검증 실행 ID
     * @param validationMonth 검증 대상 월
     * @return 선별된 검증 대상 건수
     * @author hjKang
     * @since 2026-08-13
     */
    @Transactional
    public int selectTargets(Long validationRunId, LocalDate validationMonth) {
        if (validationRunId == null) {
            throw new IllegalArgumentException("월 통합검증 실행 ID가 없습니다.");
        }
        if (validationMonth == null) {
            throw new IllegalArgumentException("검증 대상 월이 없습니다.");
        }

        LocalDate asOfDate =
                validationMonth.withDayOfMonth(validationMonth.lengthOfMonth());

        return validationTargetSelectionMapper.insertTargets(
                validationRunId,
                validationMonth,
                asOfDate
        );
    }
}
