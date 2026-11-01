package com.susukkang.fgc.clawback.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** IF-API-83 선택 검색 조건. 월 기본값은 화면에서 정하고 서버는 생략된 기간을 제한하지 않는다. */
@Getter
@Setter
public class ClawbackCandidateSearchCondition {
    public enum DateBasis { EVENT_DATE, CONTRACT_DATE }
    public enum TriggerType { STATUS_EVENT, ARBITRAGE }
    public enum CalculationStatus { UNCALCULATED, CALCULATED, REVIEW_REQUIRED }

    private DateBasis dateBasis = DateBasis.EVENT_DATE;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;
    private String contractNo;
    private Long insurerId;
    private Long productOfferingId;
    private Long agentId;
    private TriggerType triggerType;
    private CalculationStatus calculationStatus;
    private String sort;
}
