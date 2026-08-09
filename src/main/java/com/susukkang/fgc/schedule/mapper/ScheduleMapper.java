package com.susukkang.fgc.schedule.mapper;

import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleLineInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import jakarta.validation.Valid;
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
            @Param("offset") int offset
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
     * 회차별 예상 스케줄 라인을 일괄 저장한다.
     *
     * @param lines 저장할 스케줄 라인 목록
     * @return 저장된 행 수
     */
    int insertScheduleLines(@Param("lines") List<ScheduleLineInsertDTO> lines);


}
