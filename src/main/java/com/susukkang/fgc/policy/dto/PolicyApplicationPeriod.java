package com.susukkang.fgc.policy.dto;

import java.time.LocalDate;

/** 원본 승인 기간과 구분되는 실제 계약일 적용 구간. */
public record PolicyApplicationPeriod(LocalDate from, LocalDate to, Long successorPolicyVersionId) { }
