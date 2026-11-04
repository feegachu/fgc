package com.susukkang.fgc.agenthome.dto;

import java.time.OffsetDateTime;

/** IF-API-74A 응답. 같은 멱등키 재요청에도 처음 기록의 값이 그대로 나온다. */
public record ContractCheckResponse(long checkId, OffsetDateTime checkedAt, long contractId, String contextVersion) {
}
