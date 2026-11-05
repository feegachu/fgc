package com.susukkang.fgc.assistant.dto;

import jakarta.validation.constraints.*;

public record PicoGuideRequest(
        @NotBlank @Size(max=60) String workflowId,
        @NotBlank @Size(max=40) String guideVersion,
        @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String paymentId,
        @NotBlank @Size(max=80) String clientContextRevision,
        @Size(max=100) @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"}) String prepareToken,
        @Size(max=100) String stepId,
        @Size(max=100) String serverStateVersion) {
    public PicoGuideRequest(String workflowId, String guideVersion, String paymentId, String clientContextRevision, String prepareToken) {
        this(workflowId, guideVersion, paymentId, clientContextRevision, prepareToken, null, null);
    }
}
