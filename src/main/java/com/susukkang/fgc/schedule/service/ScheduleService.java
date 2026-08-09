package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
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

    private FgcBusinessException validationException(
            String field,
            String detail
    ) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }
//    public List<Long> generateSchedules(Long contractId) {
//        return scheduleMapper.findGenerationContext();
//    }

}
