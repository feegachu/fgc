package com.susukkang.fgc.schedule.mapper;

import com.susukkang.fgc.schedule.dto.ScheduleHeaderInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderListDTO;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ScheduleMapper {
    List<ScheduleHeaderListDTO> findScheduleHeaders(ScheduleSearchCondition condition);
    ScheduleHeaderInsertDTO insertScheduleHeader(ScheduleHeaderInsertDTO scheduleHeaderInsertDTO);
}
