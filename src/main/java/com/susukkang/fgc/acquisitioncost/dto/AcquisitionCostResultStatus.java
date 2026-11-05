package com.susukkang.fgc.acquisitioncost.dto;

/** acquisition_cost_check.result_status와 값이 같아야 한다. 코드값은 서버가 한글 라벨과 함께 응답한다(SIR-008). */
public enum AcquisitionCostResultStatus {
    NORMAL("한도 이내"),
    VIOLATION("한도 초과"),
    REVIEW_REQUIRED("검토 필요"),
    NOT_APPLICABLE("적용 대상 아님");

    private final String label;

    AcquisitionCostResultStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
