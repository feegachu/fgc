package com.susukkang.fgc.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PicoAskResponse(Decision decision, String message, String guideVersion, String contextRevision,
        List<Evidence> evidenceRefs,
        @Schema(types={"object","null"},description="Always null for the first read-only guide-answer scope") Object validatedGuide,
        Source source, String fallbackReason) {
    public enum Decision { ANSWER_ONLY, ASK_CLARIFICATION, UNSUPPORTED }
    public enum Source { AI, FIXED }
    @Schema(name="PicoEvidenceRef")
    public record Evidence(String chunkId, String workflowId, String stepId, String title) {}
}
