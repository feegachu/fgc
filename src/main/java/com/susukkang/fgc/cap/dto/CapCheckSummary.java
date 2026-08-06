package com.susukkang.fgc.cap.dto;

import java.util.List;

/** CAP-W01 요약 카드 4장(정상/주의/위반/검토필요) 건수. */
public record CapCheckSummary(long normal, long warning, long violation, long reviewRequired) {

    public static CapCheckSummary from(List<CapCheckStatusCount> counts) {
        long normal = 0, warning = 0, violation = 0, reviewRequired = 0;
        for (CapCheckStatusCount c : counts) {
            switch (c.getResultStatus()) {
                case "NORMAL" -> normal = c.getCount();
                case "WARNING" -> warning = c.getCount();
                case "VIOLATION" -> violation = c.getCount();
                case "REVIEW_REQUIRED" -> reviewRequired = c.getCount();
                default -> { }
            }
        }
        return new CapCheckSummary(normal, warning, violation, reviewRequired);
    }
}
