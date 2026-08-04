package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.schedule.dto.ScheduleHeaderListDTO;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;

    public List<ScheduleHeaderListDTO> getSchedules(
            ScheduleSearchCondition condition
    ) {
        return scheduleMapper.findScheduleHeaders(condition);
    }

    public List<Long> generateSchedules(
            Long contractId
    ) {
        return scheduleMapper.findGenerationContext();
    }
}
