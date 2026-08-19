package com.susukkang.fgc.exceptioncase.mapper;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseListRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionTypeSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * FGC-UI-EXCP-W01 예외함 조회 매퍼 (IF-API-43 의 목록 부분).
 * 쓰기는 validation 쪽 {@link com.susukkang.fgc.validation.mapper.ExceptionCaseMapper} —
 * 화면 조회와 배치 생성을 섞지 않는다.
 *
 * statuses 는 {@link ExceptionStatus#dbStatuses} 가 화면 필터에서 이미 풀어 준
 * DB 상태값 목록이다. 빈 목록이면 상태 필터 없음(전체).
 */
@Mapper
public interface ExceptionCaseQueryMapper {

    List<ExceptionCaseListRow> findCases(@Param("statuses") List<ExceptionStatus> statuses);

    /** 체크리스트 등 화면 바로가기의 선택 검색조건을 적용한 서버 렌더링 목록 조회. */
    List<ExceptionCaseListRow> findCasesByCriteria(
            @Param("criteria") ExceptionCaseSearchDTO criteria,
            @Param("statuses") List<ExceptionStatus> statuses);

    long countByStatuses(@Param("statuses") List<ExceptionStatus> statuses);

    /** IF-API-43 검색조건에 맞는 현재 페이지의 예외를 조회한다. */
    List<ExceptionCaseSearchRow> search(
            @Param("criteria") ExceptionCaseSearchDTO criteria,
            @Param("statuses") List<ExceptionStatus> statuses,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** search와 동일한 조건의 전체 건수를 조회한다. */
    long count(
            @Param("criteria") ExceptionCaseSearchDTO criteria,
            @Param("statuses") List<ExceptionStatus> statuses);

    /** 현재 페이지 예외들의 처리 이력을 한 번에 조회하여 N+1 쿼리를 방지한다. */
    List<ExceptionActionRow> findActionsByCaseIds(
            @Param("exceptionCaseIds") List<Long> exceptionCaseIds);

    /** 화면 상단 유형별 미처리(NEW+IN_REVIEW) 요약 카드 집계. */
    List<ExceptionTypeSummaryRow> countOpenByType();
}
