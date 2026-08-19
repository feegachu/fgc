package com.susukkang.fgc.schedule.controller;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.web.CsvExportWriter;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.schedule.dto.*;
import com.susukkang.fgc.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    /**
     * 설명 : 검색 조건에 따라 스케줄을 조회한다
     *
     * @param condition,page,size 스케줄 계약 조건 및 페이지 정보
     * @return ApiResponse<PageResponse<ScheduleResponse>> 검색 조건에 해당하는 스케줄목록 및 페이지 정보
     * @author hjKang
     * @since 2026-08-07
     */
    @GetMapping
    public ApiResponse<PageResponse<ScheduleHeaderResponse>> getSchedulesByCondition(
            @ModelAttribute @Valid ScheduleSearchCondition condition,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(scheduleService.selectByCondition(condition,page,size));
    }

    /** SCHE-W01 검색 결과 전체를 CSV로 내려받는다. */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportSchedules(@ModelAttribute @Valid ScheduleSearchCondition condition) {
        List<List<?>> rows = new ArrayList<>();
        for (ScheduleHeaderResponse schedule : scheduleService.selectAllByCondition(condition)) {
            rows.add(CsvExportWriter.row(
                    schedule.getContractNo(), schedule.getPaymentStageLabel(), schedule.getScheduleRegimeLabel(),
                    schedule.getSchedulePurposeLabel(), schedule.getStatusLabel(), schedule.getPolicyVersionLabel(),
                    schedule.getScheduleVersionNo(), schedule.getLineCount(), schedule.getExpectedTotal(),
                    Boolean.TRUE.equals(schedule.getActiveYn()) ? "사용중" : "미사용",
                    schedule.getGenerationReason(), schedule.getGeneratedAt()
            ));
        }
        String filename = "예상스케줄목록_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(CsvExportWriter.write(List.of(
                        "계약번호", "지급단계", "적용 체계", "용도", "상태", "정책 버전", "버전", "회차 수", "예상 총액", "사용 여부", "생성 사유", "생성 일시"
                ), rows));
    }

    /** SCHE-W02의 회차 표 전체를 CSV로 내려받는다. */
    @GetMapping(value = "/{scheduleHeaderId}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportScheduleLines(@PathVariable Long scheduleHeaderId) {
        ScheduleDetailResponse detail = scheduleService.selectScheduleDetailById(scheduleHeaderId);
        List<List<?>> rows = new ArrayList<>();
        List<ScheduleLineResponse> lines = detail.getLines() == null ? List.of() : detail.getLines();
        for (ScheduleLineResponse line : lines) {
            rows.add(CsvExportWriter.row(
                    line.getLineNo(), line.getInstallmentNo(), line.getContractMonthNo(), line.getDueDate(),
                    line.getCommissionItemName(), line.getRecipientName(), line.getBasisCode(), line.getBasisAmount(),
                    line.getCalculationType(), line.getRatePct(), line.getExpectedAmount(), line.getLineStatus(), line.getRuleRef()
            ));
        }
        String filename = "예상스케줄_" + detail.getHeader().getContractNo() + "_v"
                + detail.getHeader().getScheduleVersionNo() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(CsvExportWriter.write(List.of(
                        "줄번호", "회차", "계약차월", "지급예정일", "수수료 항목", "수령자", "기준코드", "기준금액", "계산방식", "요율", "예상금액", "상태", "수수료 규칙 ID"
                ), rows));
    }

    /**
     * 설명 : 회차보기 버튼을 눌러 스케줄의 회차별 예상 금액을 확인한다
     *
     * @param  scheduleHeaderId 스케줄 헤더 Id
     * @return 스케줄 세부 내역
     * @author hjKang
     * @since 2026-08-07
     */
    @GetMapping("/{scheduleHeaderId}")
    public ApiResponse<ScheduleDetailResponse> getScheduleLineById(@PathVariable Long scheduleHeaderId) {
        return ApiResponse.success(scheduleService.selectScheduleDetailById(scheduleHeaderId));
    }

    @GetMapping("/{scheduleHeaderId}/versions")
    public ApiResponse<java.util.List<ScheduleHeaderResponse>> getScheduleVersions(
            @PathVariable Long scheduleHeaderId) {
        return ApiResponse.success(scheduleService.selectScheduleVersions(scheduleHeaderId));
    }

    /**
     * 설명 : 스케줄을 새 버전으로 재생성한다.
     *
     * @param  id 스케줄ID
     * @return
     * @author hjKang
     * @since 2026-08-11
     */
    @PreAuthorize(Roles.CAN_PROCESS)
    @PostMapping("/{id}/regenerate")
    public ApiResponse<ScheduleRegenResponse> regenerate(
            @PathVariable Long id,
            @Valid @RequestBody ScheduleRegenRequest request) {
        return ApiResponse.success(scheduleService.regenerateSchedules(id, request.getReason()));
    }

    /**
     * 설명 : 예정 스케줄을 확정하여 회차별 예상 금액을 잠근다.
     *
     * @param id 스케줄 헤더 ID
     * @return 확정된 스케줄 상세
     * @author hjKang
     * @since 2026-08-16
     *
     * 2026-08-16 - 예상 스케줄 확정 API 추가
     * 기존 코드: 상세 화면에 확정 버튼만 있고 상태를 변경할 API가 없었다.
     * 문제: PLANNED 스케줄을 CONFIRMED로 전환할 수 없어 DB의 확정 후 불변성 규칙을 사용할 수 없었다.
     * 개선: 정산 권한 사용자가 활성 예정 스케줄을 확정할 수 있는 API를 제공한다.
     */
    @PreAuthorize(Roles.CAN_PROCESS)
    @PostMapping("/{id}/confirm")
    public ApiResponse<ScheduleDetailResponse> confirm(@PathVariable Long id) {
        return ApiResponse.success(scheduleService.confirmSchedule(id));
    }
}
