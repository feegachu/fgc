package com.susukkang.fgc.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.common.exception.*;
import java.math.BigDecimal;
import java.util.Map;

/** assistant.v1 decimal strings; parsing never uses floating point or model output. */
public final class CalculationAmounts {
    private CalculationAmounts() {}
    public static BigDecimal input(JsonNode value, String field) {
        if (value == null || !value.isTextual() || !value.textValue().matches("(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,2})?")) throw invalid(field);
        return new BigDecimal(value.textValue());
    }
    public static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002,field,Map.of("field",field),"계산 입력 형식과 범위를 확인하세요.");
    }
    public static long id(String value, String field) {
        try { long id=Long.parseLong(value); if(id<=0) throw invalid(field); return id; }
        catch(NumberFormatException|NullPointerException e) {throw invalid(field);}
    }
}
