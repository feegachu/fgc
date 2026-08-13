package com.susukkang.fgc.validation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 설명 : ValidationTargetSelectionMapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */
@Mapper
public interface ValidationTargetSelectionMapper {
    int insertTargets(
            @Param("validationRunId") Long validationRunId,
            @Param("validationMonth") LocalDate validationMonth
    );
}
