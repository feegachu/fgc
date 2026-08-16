package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.InsurerRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InsurerMapper {

    List<InsurerRow> selectInsurers(
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countInsurers(@Param("keyword") String keyword);
}
