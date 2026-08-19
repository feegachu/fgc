package com.susukkang.fgc.base.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "보험설계사 기준정보")
public record AgentResponse(
        @Schema(description = "설계사 ID", example = "101")
        Long agentId,
        @Schema(description = "설계사 코드", example = "FC-SEOUL-001")
        String agentCode,
        @Schema(description = "설계사명", example = "김설계")
        String agentName,
        @Schema(description = "직급 코드", example = "FC")
        String rankCode,
        @Schema(description = "직급 한글명", example = "설계사")
        String rankLabel,
        @Schema(description = "소속 조직 ID", example = "11")
        Long organizationId,
        @Schema(description = "소속 조직 코드", example = "FGC-BR-SEOUL")
        String organizationCode,
        @Schema(description = "소속 조직명", example = "서울지사")
        String organizationName,
        @Schema(description = "위촉일", example = "2026-05-01")
        LocalDate appointmentDate,
        @Schema(description = "해촉일", nullable = true)
        LocalDate terminationDate,
        @Schema(description = "설계사 상태 코드", allowableValues = {"ACTIVE", "INACTIVE", "TERMINATED"})
        String agentStatus,
        @Schema(description = "설계사 상태 한글명", example = "활동")
        String agentStatusLabel,
        @Schema(description = "최근 등록일", nullable = true)
        LocalDate latestRegistrationDate,
        @Schema(description = "최근 등록일 직전 3년 모집경력 여부", nullable = true)
        Boolean priorThreeYearExperienceYn,
        @Schema(description = "경력 조회 기준일", nullable = true)
        LocalDate experienceCheckedOn,
        @Schema(description = "신인활동지원 대상 여부")
        boolean newcomerSupportEligibleYn,
        @Schema(description = "신인활동지원 종료일", nullable = true)
        LocalDate newcomerSupportEndDate,
        @Schema(description = "기준정보 활성 여부. false이면 신규 입력에서 선택할 수 없음")
        boolean activeYn
) {
    public static AgentResponse from(AgentRow row) {
        return new AgentResponse(
                row.agentId(),
                row.agentCode(),
                row.agentName(),
                row.rankCode(),
                rankLabel(row.rankCode()),
                row.organizationId(),
                row.organizationCode(),
                row.organizationName(),
                row.appointmentDate(),
                row.terminationDate(),
                row.agentStatus(),
                statusLabel(row.agentStatus()),
                row.latestRegistrationDate(),
                row.priorThreeYearExperienceYn(),
                row.experienceCheckedOn(),
                row.newcomerSupportEligibleYn(),
                row.newcomerSupportEndDate(),
                row.activeYn()
        );
    }

    private static String rankLabel(String rankCode) {
        if (rankCode == null) {
            return null;
        }
        return switch (rankCode) {
            case "FC" -> "설계사";
            case "TEAM_LEADER" -> "팀장";
            case "BRANCH_MANAGER" -> "지점장";
            case "DIVISION_HEAD" -> "본부장";
            default -> rankCode;
        };
    }

    private static String statusLabel(String agentStatus) {
        if (agentStatus == null) {
            return null;
        }
        return switch (agentStatus) {
            case "ACTIVE" -> "활동";
            case "INACTIVE" -> "비활동";
            case "TERMINATED" -> "해촉";
            default -> agentStatus;
        };
    }
}
