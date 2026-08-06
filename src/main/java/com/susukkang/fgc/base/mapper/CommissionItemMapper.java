package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CommissionItemMapper {

    List<CommissionItemResponse> findEffectiveItems(@Param("asOf") LocalDate asOf);
}
