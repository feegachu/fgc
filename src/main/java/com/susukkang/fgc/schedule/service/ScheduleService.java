package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;

    public PageResponse<ScheduleHeaderResponse> selectByCondition(
            @Valid ScheduleSearchCondition condition, int page, int size) {
        return null;
    }

//    public List<Long> generateSchedules(Long contractId) {
//        return scheduleMapper.findGenerationContext();
//    }

}
