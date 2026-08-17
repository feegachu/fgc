package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.ProductRow;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProductMapper {

    List<ProductRow> selectProducts(
            @Param("criteria") ProductSearchCriteria criteria,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countProducts(@Param("criteria") ProductSearchCriteria criteria);
}
