package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CapCheckMapper {

    /** cap_check는 append-only 스냅샷. INSERT 후 row.capCheckId에 생성된 PK 가 채워짐 */
    void insertCapCheck(CapCheckInsertRow row);

    /** cap_check_detail 일괄 INSERT. 항목이 없으면 호출 X */
    void insertCapCheckDetails(@Param("details") List<CapCheckDetailInsertRow> details);
}
