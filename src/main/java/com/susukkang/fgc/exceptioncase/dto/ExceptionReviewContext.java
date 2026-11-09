package com.susukkang.fgc.exceptioncase.dto;

import java.time.LocalDate;
import java.util.List;

public record ExceptionReviewContext(String contractStatus, LocalDate contractDate, boolean offeringMismatch,
        boolean rejectionRequiresEvidence, boolean administratorReviewRequired, boolean recheckAvailable,
        String guidance, List<Link> links) {
    public record Link(String label, String href) {}
}
