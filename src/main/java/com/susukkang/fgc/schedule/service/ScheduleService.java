package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractResponse;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.schedule.dto.ScheduleDetailResponse;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleLineResponse;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;
    private final ContractMapper contractMapper;

    /**
     * 설명 : 검색 조건에 따라 스케줄 헤더를 조회한다.
     * 검색 조건과 현재 페이지 , 최대 스케줄 개수를 받아
     * 스케줄 헤더 목록을 출력하고 최대페이지 , 현재페이지를 PageResponse를 통해 출력
     *
     * @param condition 조회 조건
     * @param page 현재 페이지
     * @param size 한 페이지에 출력할 스케줄 헤더 수
     * @return List<ContractListDTO> 조회된 보험계약 목록
     * @author hjKang
     * @since 2026-08-05
     */
    public PageResponse<ScheduleHeaderResponse> selectByCondition(
            @Valid ScheduleSearchCondition condition, int page, int size) {
        //입력값 검증
        if (page < 1) {
            throw validationException(
                    "page",
                    "page는 1 이상이어야 합니다."
            );
        }

        if (size < 1 || size > 100) {
            throw validationException(
                    "size",
                    "size는 1 이상 100 이하여야 합니다."
            );
        }

        // offset : DB가 앞에서 건널 뛸 행 개수 -> offset 번째 부터 조회함
        long offsetLong = (long)( page - 1 ) * size;

        int offset = (int) offsetLong;
        List<ScheduleHeaderResponse> scheduleHeaderList = scheduleMapper.selectByCondition(condition,size,offset);
        // 검색조건에 해당하는 전체 계약 건수 조회
        long totalContracts =
                scheduleMapper.countByCondition(condition);

        return PageResponse.of(
                scheduleHeaderList,
                page,
                size,
                totalContracts,
                "scheduleHeaderId,desc"
        );
    }

    private FgcBusinessException validationException(String field, String detail) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }
    /**
     * 설명 : 스케줄 헤더의 상세보기를 눌러 스케줄 상세보기를 조회한다.
     * @param scheduleHeaderId 스케줄 아이디
     * @return ScheduleDetailResponse 조회된 보험계약 목록
     * @author hjKang
     * @since 2026-08-09
     */
    public ScheduleDetailResponse selectScheduleDetailById(Long scheduleHeaderId) {
        // 스케줄 Id 검증 및 가져오기
        ScheduleDetailResponse detail  = scheduleMapper.selectScheduleDetailById(scheduleHeaderId);

        if (detail == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "scheduleHeaderId",
                    Map.of("scheduleHeaderId", scheduleHeaderId),
                    "해당 스케줄을 찾을 수 없습니다."
            );
        }

        // TODO(FUN-039, 1차) 스케줄 상태·조정·버전 관리
        // 스케줄 상태 조정 버전 가져오기

        return detail ;
    }
    /**
     * 설명 : 계약 ID에 따라 회차별 스케줄을 자동 생성한다
     * @param contract 계약 class
     * @return scheduleCounts 생성된 스케줄 라인 수
     * @author hjKang
     * @since 2026-08-10
     */
    public int generateSchedules(InsuranceContract contract) {
        // 입력값 검증
        if (contract == null || contract.getContractNo() == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.CONT_001,
                    "contractNo",
                    Map.of("contractNo", ""),
                    "계약번호가 존재하지 않습니다."
            );
        }

        Long contractId = contract.getContractId();

        // 계약 존재 여부 확인
        InsuranceContract savedContract = contractMapper.selectById(contractId);

        if (savedContract == null) {   //일단 보류 001은 계약 중복 코드이므로 이따 추가함
            throw new FgcBusinessException(
                    FgcErrorCode.CONT_001,
                    "contractId",
                    Map.of("contractId", contractId),
                    "계약ID가 존재하지 않습니다."
            );
        }

        // 해당 계약의 정책 룰셋 조회
        // TODO FUN-011 현행 수수료 정책 조회 적용

        // 스케줄 헤더 생성

        // 스케줄 라인 생성
        return 0;
    }
}
