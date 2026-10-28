package com.susukkang.fgc.assistant.dto;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.List;

public record PicoAskRequest(
        @NotBlank @Size(max=2000) String question,
        @NotBlank @Size(max=30) String screenId,
        @NotNull @Size(max=6) List<@NotNull @Valid EntityRef> entityRefs,
        @NotBlank @Size(max=80) String clientContextRevision,
        @NotBlank @Size(max=40) String guideVersion,
        @Size(max=6) List<@NotNull @Valid Turn> conversation) {
    public enum EntityType { CONTRACT, PAYMENT, SCHEDULE, VALIDATION_RUN, RECONCILIATION_RUN, EXCEPTION }
    public enum Role { USER, ASSISTANT }
    @Schema(name="PicoEntityRef")
    public record EntityRef(@NotNull EntityType type, @NotNull @Positive Long id) {}
    @Schema(name="PicoConversationTurn")
    public record Turn(@NotNull Role role, @NotBlank @Size(max=2000) String text) {}
}
