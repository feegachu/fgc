package com.susukkang.fgc.assistant.dto;

import java.time.Instant;

/** Authorizes the first guide highlight only, never a payment or precheck. */
public record PicoGuideResponse(String status, String workflowId, String stepId, String guideVersion,
        String paymentId, String clientContextRevision, Instant expiresAt, @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"}) String prepareToken) {}
