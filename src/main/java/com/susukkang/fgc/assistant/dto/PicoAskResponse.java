package com.susukkang.fgc.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PicoAskResponse(Decision decision, String message, String guideVersion, String contextRevision,
        List<Evidence> evidenceRefs,
        @Schema(description="서버가 등록·근거·권한·최신 상태를 검증한 시작 위치. START_GUIDE에만 존재") ValidatedGuide validatedGuide,
        Source source, String fallbackReason) {
    public enum Decision { ANSWER_ONLY, START_GUIDE, ASK_CLARIFICATION, UNSUPPORTED }
    @Schema(name="PicoValidatedGuide")
    public record ValidatedGuide(String workflowId, String stepId, String guideVersion,
            List<PicoAskRequest.EntityRef> entityRefs, String clientContextRevision,
            @Schema(types={"string","null"}) String serverStateVersion) {}
    public enum Source { AI, FIXED }
    @Schema(name="PicoEvidenceRef")
    public record Evidence(String chunkId, String workflowId, String stepId, String title) {}
}
