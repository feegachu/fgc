package com.susukkang.fgc.exceptioncase.mapper;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseListRow;
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

    long countByStatuses(@Param("statuses") List<ExceptionStatus> statuses);
}
