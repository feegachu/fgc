package com.susukkang.fgc.schedule.repository;

import com.susukkang.fgc.schedule.entity.ScheduleHeader;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 설명 : 예상 스케줄 헤더의 기본 저장 Repository. 조건부 상태 전이는 ScheduleWriteRepository가 담당한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-05
 */
public interface ScheduleHeaderRepository extends JpaRepository<ScheduleHeader, Long> {
}
