package com.susukkang.fgc.schedule.mapper;

import com.susukkang.fgc.schedule.dto.*;
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

    /**
     * 설명 : 스케줄 라인 목록을 schedule_line에 일괄 저장한다.
     * @param scheduleLineList 스케줄 라인 목록
     * @return 삽입된 스케줄 라인의 수
     * @author hjKang
     * @since 2026-08-09
     */
    int insertAllScheduleLines(@Param("lines") List<ScheduleLineInsertDTO> scheduleLineList);
}
