package com.susukkang.fgc.arbitrage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 설명 : 계약 단건 차익거래 수동 검증 요청 정보를 전달한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ReArbitrageCheckRequest {
    @NotNull
    private LocalDate asOfDate; // 검증 기준일

    @NotBlank
    @Size(max = 200)
    private String reason; // 수동 검증 사유
}
