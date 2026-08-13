package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.OrganizationRow;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrganizationMapper {

    List<OrganizationRow> selectOrganizations(
            @Param("criteria") OrganizationSearchCriteria criteria,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countOrganizations(@Param("criteria") OrganizationSearchCriteria criteria);
}
